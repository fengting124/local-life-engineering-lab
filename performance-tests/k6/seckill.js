import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MOBILE_BASE = Number(__ENV.MOBILE_BASE || 13900000000);
const MOBILE_COUNT = Number(__ENV.MOBILE_COUNT || 2000);
const LOGIN_CODE = __ENV.LOGIN_CODE || '123456';
const SESSIONS = (__ENV.SECKILL_SESSIONS || '1:1,2:2')
  .split(',')
  .map((pair) => {
    const [sessionId, couponTemplateId] = pair.split(':').map(Number);
    return { sessionId, couponTemplateId };
  });

const SUMMARY_PATH = __ENV.SUMMARY_PATH || 'performance-tests/reports/seckill-k6-summary.json';
const EXPECTED_STOCK = Number(__ENV.EXPECTED_STOCK || 1000);

export const claimedSuccess = new Counter('claimed_success');
export const businessSoldOut = new Counter('business_sold_out');
export const businessDuplicate = new Counter('business_duplicate');
export const rateLimited = new Counter('rate_limited');
export const unexpectedError = new Counter('unexpected_error');
export const unexpectedErrorRate = new Rate('unexpected_error_rate');
export const seckillLatency = new Trend('seckill_latency', true);

export const options = {
  scenarios: {
    seckill_spike: {
      executor: 'ramping-vus',
      stages: [
        { duration: '10s', target: Number(__ENV.USERS || 200) },
        { duration: __ENV.RUN_TIME || '30s', target: Number(__ENV.USERS || 200) },
        { duration: '5s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    seckill_latency: ['p(95)<200', 'p(99)<500'],
    unexpected_error_rate: ['rate<0.01'],
  },
};

let token = '';
let mobile = '';

function clientIpFor(index) {
  const second = Math.floor(index / 65536) % 256;
  const third = Math.floor(index / 256) % 256;
  const fourth = (index % 250) + 1;
  return `10.${second}.${third}.${fourth}`;
}

function loginOnce() {
  if (token) {
    return true;
  }

  const idx = (exec.vu.idInTest - 1) % MOBILE_COUNT;
  mobile = String(MOBILE_BASE + idx);
  const response = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ mobile, code: LOGIN_CODE }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-Forwarded-For': clientIpFor(idx),
      },
      tags: { name: '/api/v1/auth/login [setup]' },
    },
  );

  const ok = check(response, {
    'login status is 200': (r) => r.status === 200,
    'login returns token': (r) => Boolean(r.json('data.token')),
  });

  if (!ok) {
    unexpectedError.add(1);
    unexpectedErrorRate.add(1);
    return false;
  }

  token = response.json('data.token');
  return true;
}

export default function () {
  if (!loginOnce()) {
    sleep(1);
    return;
  }

  const session = SESSIONS[Math.floor(Math.random() * SESSIONS.length)];
  const response = http.post(
    `${BASE_URL}/api/v1/seckill`,
    JSON.stringify(session),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      tags: { name: '/api/v1/seckill' },
    },
  );

  seckillLatency.add(response.timings.duration);

  if (response.status === 200) {
    const success = response.json('data.success') === true;
    if (success) {
      claimedSuccess.add(1);
    } else {
      businessSoldOut.add(1);
    }
    unexpectedErrorRate.add(0);
    return;
  }

  if (response.status === 400) {
    const body = response.body || '';
    if (body.includes('ALREADY') || body.includes('已领取')) {
      businessDuplicate.add(1);
    } else {
      businessSoldOut.add(1);
    }
    unexpectedErrorRate.add(0);
    return;
  }

  if (response.status === 429) {
    rateLimited.add(1);
    unexpectedErrorRate.add(0);
    return;
  }

  unexpectedError.add(1);
  unexpectedErrorRate.add(1);
}

function metricCount(data, name) {
  return data.metrics[name]?.values?.count || 0;
}

export function handleSummary(data) {
  const claimed = metricCount(data, 'claimed_success');
  const expectedTotalStock = EXPECTED_STOCK * SESSIONS.length;
  const overSold = claimed > expectedTotalStock;
  const lines = [
    '',
    'LocalLife seckill k6 summary',
    `BASE_URL=${BASE_URL}`,
    `sessions=${SESSIONS.map((s) => `${s.sessionId}:${s.couponTemplateId}`).join(',')}`,
    `claimed_success=${claimed}`,
    `expected_total_stock<=${expectedTotalStock}`,
    `business_sold_out=${metricCount(data, 'business_sold_out')}`,
    `business_duplicate=${metricCount(data, 'business_duplicate')}`,
    `rate_limited=${metricCount(data, 'rate_limited')}`,
    `unexpected_error=${metricCount(data, 'unexpected_error')}`,
    `oversold=${overSold ? 'YES' : 'NO'}`,
    '',
  ].join('\n');

  return {
    stdout: lines,
    [SUMMARY_PATH]: JSON.stringify(data, null, 2),
  };
}
