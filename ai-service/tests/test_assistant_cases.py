"""Mock确定性回归，不能把通过率当成真实模型效果。"""
import asyncio
import json
from pathlib import Path

import pytest

from app.assistant import MockPlanner, PlanRequest

FIXTURE = json.loads((Path(__file__).parent / 'fixtures' / 'assistant_cases.json').read_text(encoding='utf-8'))


@pytest.mark.parametrize('case', FIXTURE['cases'], ids=lambda case: case['id'] + '-' + case['split'])
def test_mock_fixed_contract_cases(case):
    body = PlanRequest.model_validate({**FIXTURE['defaultContext'], **{key: value for key, value in case.items() if key not in ('id', 'split', 'expected')}})
    result = asyncio.run(MockPlanner().plan(body))
    assert result.mode == 'MOCK'
    expected = case['expected']
    if expected.get('clarification'):
        assert result.toolCalls == []
        assert result.message
    else:
        assert len(result.toolCalls) == 1
        assert result.toolCalls[0].name == expected['tool']
        assert result.toolCalls[0].arguments == expected['arguments']
