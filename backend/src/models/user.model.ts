import { User, UserRole } from '@prisma/client';

export type UserCreateInput = {
  username: string;
  email: string;
  password: string;
  role?: UserRole;
};

export type UserUpdateInput = {
  username?: string;
  email?: string;
  password?: string;
  role?: UserRole;
};

export type UserSafe = Omit<User, 'passwordHash'>;

export type LoginInput = {
  username: string;
  password: string;
};

export type AuthResponse = {
  token: string;
  refreshToken: string;
  user: UserSafe;
};

