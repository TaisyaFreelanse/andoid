const { PrismaClient } = require('@prisma/client');
const bcrypt = require('bcrypt');

const prisma = new PrismaClient();

async function main() {
  console.log('Creating admin user...');
  
  const username = 'admin';
  const password = 'admin123';
  const email = 'admin@example.com';
  
  const adminPassword = await bcrypt.hash(password, 10);
  const admin = await prisma.user.upsert({
    where: { username: username },
    update: {
      passwordHash: adminPassword,
      role: 'admin',
      email: email,
    },
    create: {
      username: username,
      email: email,
      passwordHash: adminPassword,
      role: 'admin',
    },
  });
  
  console.log('✅ Admin user created/updated successfully!');
  console.log('Username:', admin.username);
  console.log('Email:', admin.email);
  console.log('Password:', password);
  console.log('Role:', admin.role);
}

main()
  .catch((e) => {
    console.error('Error:', e);
    process.exit(1);
  })
  .finally(async () => {
    await prisma.$disconnect();
  });

