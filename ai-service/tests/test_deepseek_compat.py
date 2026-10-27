"""真实 LangChain/OpenAI SDK + 内存 HTTP；不读取真实 Key、不访问模型。"""
import asyncio
import json
from dataclasses import replace
from urllib.parse import urlsplit

import httpx
import pytest
from fastapi.testclient import TestClient

from app.assistant import AssistantConfig, CONTRACT, ModelPlanner, PlanRequest
from app.main import create_app


TOKEN = 'synthetic-deepseek-service-token-32-characters'
API_KEY = 'synthetic-deepseek-api-key'
CONFIG = AssistantConfig(enabled=True, token=TOKEN, api_key=API_KEY,
                         model='deepseek-flash', base_url='https://api.deepseek.com/beta')
ARGUMENTS = {'month': '2026-10', 'scope': 'SELF', 'deptId': None}


def body(**changes):
    return {'contractVersion': CONTRACT, 'message': '查询我的本月考勤', 'today': '2026-10-27',
            'departments': [{'id': 7, 'name': '合成研发部'}], 'history': [], 'steps': [],
            'allowExport': True, **changes}


def provider_response(arguments=None):
    return {'id': 'synthetic-deepseek-response', 'object': 'chat.completion', 'created': 1,
            'model': 'deepseek-flash', 'choices': [{'index': 0, 'finish_reason': 'tool_calls',
                'message': {'role': 'assistant', 'content': None, 'tool_calls': [{
                    'id': 'synthetic-deepseek-call', 'type': 'function', 'function': {
                        'name': 'query_attendance_summary',
                        'arguments': json.dumps(ARGUMENTS if arguments is None else arguments)}}]}}],
            'usage': {'prompt_tokens': 30, 'completion_tokens': 12, 'total_tokens': 42}}


async def plan_with_transport(monkeypatch, config, transport):
    import langchain_openai
    original = langchain_openai.ChatOpenAI
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport), trust_env=False) as http_client:
        def create(**options):
            return original(**options, http_async_client=http_client)
        monkeypatch.setattr(langchain_openai, 'ChatOpenAI', create)
        return await ModelPlanner(config).plan(PlanRequest.model_validate(body()))


@pytest.mark.parametrize('base_url,deepseek', [
    ('https://api.deepseek.com/beta', True),
    ('https://api.deepseek.com/beta/', True),
    ('https://API.DEEPSEEK.COM/beta', True),
    ('https://api.deepseek.com.evil.invalid/beta', False),
    ('https://api-deepseek.com.invalid/beta', False),
    ('https://synthetic-provider.invalid/deepseek/beta', False),
])
def test_sdk_routes_private_options_only_to_exact_deepseek_host(monkeypatch, base_url, deepseek):
    captured = []
    def transport(request):
        captured.append(request)
        return httpx.Response(200, json=provider_response())
    result = asyncio.run(plan_with_transport(monkeypatch, replace(CONFIG, base_url=base_url), transport))
    assert result.mode == 'MODEL'
    assert result.toolCalls[0].arguments == ARGUMENTS
    assert result.usage.model_dump() == {'inputTokens': 30, 'outputTokens': 12}
    assert len(captured) == 1
    request = captured[0]
    assert request.url.path == urlsplit(base_url).path.rstrip('/') + '/chat/completions'
    assert request.method == 'POST'
    assert request.headers['authorization'] == 'Bearer ' + API_KEY
    payload = json.loads(request.content)
    assert payload['model'] == 'deepseek-flash'
    assert payload['temperature'] == 0
    assert payload['parallel_tool_calls'] is False
    assert payload['stream'] is False
    assert 'extra_body' not in payload
    if deepseek:
        assert payload['thinking'] == {'type': 'disabled'}
        assert payload['max_tokens'] == 600
        assert 'max_completion_tokens' not in payload
    else:
        assert 'thinking' not in payload
        assert 'max_tokens' not in payload
        assert payload['max_completion_tokens'] == 600
    assert len(payload['tools']) == 4
    by_name = {tool['function']['name']: tool['function'] for tool in payload['tools']}
    for function in by_name.values():
        schema = function['parameters']
        assert function['strict'] is True
        assert schema['type'] == 'object'
        assert schema['additionalProperties'] is False
        assert set(schema['required']) == set(schema['properties'])
    properties = by_name['query_attendance_summary']['parameters']['properties']
    assert properties['month']['pattern'] == r'^\d{4}-\d{2}$'
    assert properties['scope']['enum'] == ['SELF', 'DEPARTMENT']
    assert properties['deptId']['anyOf'] == [
        {'minimum': 1, 'maximum': 2_147_483_647, 'type': 'integer'}, {'type': 'null'}]
    assert by_name['get_export_status']['parameters']['properties']['jobId']['maximum'] == 9_223_372_036_854_775_807
    assert TOKEN not in request.content.decode()
    assert API_KEY not in request.content.decode()


def test_deepseek_sdk_failure_does_not_retry_fallback_or_echo_private_response(monkeypatch, caplog):
    import langchain_openai
    original = langchain_openai.ChatOpenAI
    captured = []
    def transport(request):
        captured.append(request)
        return httpx.Response(429, json={'error': {'message': 'private-provider-detail ' + API_KEY,
                                                'type': 'rate_limit_error'}})
    async_client = httpx.AsyncClient(transport=httpx.MockTransport(transport), trust_env=False)
    def create(**options):
        assert options['max_retries'] == 0
        return original(**options, http_async_client=async_client)
    monkeypatch.setattr(langchain_openai, 'ChatOpenAI', create)
    try:
        with TestClient(create_app(token='', assistant_config=CONFIG)) as client:
            value = client.post('/assistant/v1/plan', headers={'X-Assistant-Service-Token': TOKEN}, json=body())
        assert value.status_code == 503
        assert value.json() == {'code': 'MODEL_UNAVAILABLE', 'msg': '模型服务暂不可用，请检查配置或稍后重试'}
        assert len(captured) == 1
        assert 'MOCK' not in value.text
        assert 'private-provider-detail' not in value.text + caplog.text
        assert API_KEY not in value.text + caplog.text
        assert TOKEN not in value.text + caplog.text
    finally:
        asyncio.run(async_client.aclose())


@pytest.mark.parametrize('arguments', [
    {'month': '2026-10', 'scope': 'SELF', 'deptId': 7},
    {'month': '2026-10', 'scope': 'DEPARTMENT', 'deptId': None},
    {'month': '2026-10', 'scope': 'DEPARTMENT', 'deptId': 99},
    {'month': '2026-10', 'scope': 'SELF', 'deptId': None, 'userId': 1},
])
def test_deepseek_sdk_output_still_has_strict_business_validation(monkeypatch, arguments):
    from app.assistant import AssistantError
    def transport(request):
        return httpx.Response(200, json=provider_response(arguments))
    with pytest.raises(AssistantError) as error:
        asyncio.run(plan_with_transport(monkeypatch, CONFIG, transport))
    assert error.value.code == 'MODEL_INVALID_RESPONSE'
