import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
const serverErrors = new Counter('server_errors');
const transportErrors = new Counter('transport_errors');
export const options = {
  scenarios: { checkout: { executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 20), timeUnit: '1s', duration: __ENV.DURATION || '5m', preAllocatedVUs: 100, maxVUs: 100 } },
  summaryTrendStats: ['min','med','p(95)','p(99)','max'],
};
export default function () {
  const response = http.post(`${__ENV.BASE_URL || 'http://localhost:8081'}/api/checkout`, null, { headers: { 'X-Request-Id': `${exec.scenario.iterationInTest}` }, tags: { name: 'checkout' } });
  serverErrors.add(response.status >= 500 ? 1 : 0);
  transportErrors.add(response.status === 0 ? 1 : 0);
  check(response, { 'response received': r => r.status >= 200 && r.status < 600 });
}
export function handleSummary(data) { return { [__ENV.SUMMARY_PATH || '/reports/summary.json']: JSON.stringify(data, null, 2) }; }
