// Simple test script for domain checking
const fetch = require('node-fetch');

const API_URL = process.env.API_URL || 'http://localhost:3000';
const TEST_DOMAINS = [
  'google.com',
  'example.com',
  'invalid-domain-that-does-not-exist-12345.com',
  'github.com'
];

async function testDomainCheck() {
  console.log('🧪 Testing domain check API...\n');
  console.log(`API URL: ${API_URL}/api/agent/test/domain-check\n`);
  
  try {
    const response = await fetch(`${API_URL}/api/agent/test/domain-check`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        domains: TEST_DOMAINS,
      }),
    });

    if (!response.ok) {
      const errorText = await response.text();
      console.error('❌ Error:', response.status, response.statusText);
      console.error('Response:', errorText);
      return;
    }

    const result = await response.json();
    
    console.log('✅ Test Results:\n');
    console.log('Summary:');
    console.log(`  Total domains: ${result.summary.total}`);
    console.log(`  Valid: ${result.summary.valid}`);
    console.log(`  Invalid: ${result.summary.invalid}`);
    console.log(`  Exists: ${result.summary.exists}`);
    console.log(`  With metrics: ${result.summary.withMetrics}\n`);
    
    console.log('Detailed Results:');
    result.results.forEach((r, i) => {
      console.log(`\n${i + 1}. ${r.domain}:`);
      console.log(`   Valid: ${r.isValid}`);
      console.log(`   Exists: ${r.exists}`);
      console.log(`   Source: ${r.source || 'N/A'}`);
      if (r.metrics) {
        console.log(`   Metrics:`);
        if (r.metrics.domainRank) console.log(`     Domain Rank: ${r.metrics.domainRank}`);
        if (r.metrics.organicKeywords) console.log(`     Organic Keywords: ${r.metrics.organicKeywords}`);
        if (r.metrics.organicTraffic) console.log(`     Organic Traffic: ${r.metrics.organicTraffic}`);
        if (r.metrics.backlinks) console.log(`     Backlinks: ${r.metrics.backlinks}`);
      }
      if (r.error) {
        console.log(`   Error: ${r.error}`);
      }
    });
    
    console.log('\n✅ Test completed successfully!');
  } catch (error) {
    console.error('❌ Test failed:', error.message);
    if (error.code === 'ECONNREFUSED') {
      console.error('\n💡 Make sure the backend server is running:');
      console.error('   cd backend && npm run dev');
    }
  }
}

testDomainCheck();

