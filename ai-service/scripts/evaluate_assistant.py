"""固定案例评测，默认仅Mock；真实模型须单独显式授权，报告不含原始模型文本。"""
import argparse
import asyncio
import json
import sys
import time
from dataclasses import replace
from pathlib import Path

SERVICE_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVICE_ROOT))
from app.assistant import AssistantConfig, MockPlanner, ModelPlanner, PlanRequest, validate_plan
from app.main import valid_token


async def evaluate(options):
    fixture = json.loads((SERVICE_ROOT / 'tests' / 'fixtures' / 'assistant_cases.json').read_text(encoding='utf-8'))
    if options.provider == 'model':
        if not options.allow_paid_model:
            raise SystemExit('真实模型可能产生费用，必须显式提供 --allow-paid-model，默认不调用。')
        config = replace(AssistantConfig.from_env(), enabled=True, provider='openai-compatible')
        reason = config.reason(valid_token)
        if reason:
            raise SystemExit('真实模型配置不完整或无效：' + reason)
        planner, mode = ModelPlanner(config), 'MODEL'
    else:
        config, planner, mode = AssistantConfig(), MockPlanner(), 'MOCK'
    results = []
    for case in fixture['cases']:
        if options.split != 'all' and case['split'] != options.split:
            continue
        body = PlanRequest.model_validate({**fixture['defaultContext'], **{key: value for key, value in case.items() if key not in ('id', 'split', 'expected')}})
        started = time.monotonic()
        try:
            result = await asyncio.wait_for(planner.plan(body), timeout=config.timeout_seconds)
            validate_plan(result, body)
            expected = case['expected']
            passed = (not result.toolCalls and bool(result.message)) if expected.get('clarification') else (
                len(result.toolCalls) == 1 and result.toolCalls[0].name == expected['tool'] and result.toolCalls[0].arguments == expected['arguments'])
            results.append({'id': case['id'], 'split': case['split'], 'passed': passed, 'elapsedMs': round((time.monotonic() - started) * 1000), 'usage': result.usage.model_dump()})
        except Exception:
            results.append({'id': case['id'], 'split': case['split'], 'passed': False, 'error': 'PLAN_FAILED'})
    report = {'fixtureVersion': fixture['version'], 'mode': mode, 'split': options.split,
              'realModelMetricsAvailable': mode == 'MODEL',
              'notice': 'Mock结果仅表示规则解析和契约回归，不代表真实AI效果、时延或费用。' if mode == 'MOCK' else '本报告仅针对所配模型与固定合成案例，不代表生产业务准确率。',
              'count': len(results), 'passed': sum(item['passed'] for item in results), 'results': results}
    if options.output:
        target = Path(options.output).resolve()
        local = SERVICE_ROOT.parent / '.local'
        if local.resolve() not in target.parents:
            raise SystemExit('评测报告只允许写入项目.local目录，避免公开真实模型运行信息。')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({key: value for key, value in report.items() if key != 'results'}, ensure_ascii=False))
    return 0 if report['passed'] == report['count'] else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--provider', choices=('mock', 'model'), default='mock')
    parser.add_argument('--split', choices=('development', 'holdout', 'all'), default='development')
    parser.add_argument('--allow-paid-model', action='store_true')
    parser.add_argument('--output')
    raise SystemExit(asyncio.run(evaluate(parser.parse_args())))
