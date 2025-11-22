import { PrismaClient } from '@prisma/client';
import bcrypt from 'bcrypt';

const prisma = new PrismaClient();

async function main() {
  console.log('🌱 Seeding database...');

  
  const adminPassword = await bcrypt.hash('admin123', 10);
  const admin = await prisma.user.upsert({
    where: { email: 'admin@example.com' },
    update: {},
    create: {
      username: 'admin',
      email: 'admin@example.com',
      passwordHash: adminPassword,
      role: 'admin',
    },
  });
  console.log('✅ Created admin user:', admin.username);

  
  const operatorPassword = await bcrypt.hash('operator123', 10);
  const operator = await prisma.user.upsert({
    where: { email: 'operator@example.com' },
    update: {},
    create: {
      username: 'operator',
      email: 'operator@example.com',
      passwordHash: operatorPassword,
      role: 'operator',
    },
  });
  console.log('✅ Created operator user:', operator.username);

  
  const viewerPassword = await bcrypt.hash('viewer123', 10);
  const viewer = await prisma.user.upsert({
    where: { email: 'viewer@example.com' },
    update: {},
    create: {
      username: 'viewer',
      email: 'viewer@example.com',
      passwordHash: viewerPassword,
      role: 'viewer',
    },
  });
  console.log('✅ Created viewer user:', viewer.username);

  
  const proxy = await prisma.proxy.upsert({
    where: { id: '00000000-0000-0000-0000-000000000001' },
    update: {},
    create: {
      id: '00000000-0000-0000-0000-000000000001',
      host: 'proxy.example.com',
      port: 8080,
      type: 'http',
      status: 'active',
      country: 'US',
      timezone: 'America/New_York',
    },
  });
  console.log('✅ Created sample proxy:', proxy.host);

  console.log('✨ Seeding completed!');
  console.log('\n📝 Default credentials:');
  console.log('Admin:    admin / admin123');
  console.log('Operator: operator / operator123');
  console.log('Viewer:   viewer / viewer123');
}

main()
  .catch((e) => {
    console.error('❌ Error seeding database:', e);
    process.exit(1);
  })
  .finally(async () => {
    await prisma.$disconnect();
  });

