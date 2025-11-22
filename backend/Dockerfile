FROM node:18-alpine AS builder

WORKDIR /app


RUN apk add --no-cache python3 make g++ openssl libc6-compat


COPY package.json package-lock.json* ./


RUN npm ci


COPY . .
COPY prisma ./prisma


RUN npx prisma generate


RUN npm run build


FROM node:18-alpine

WORKDIR /app


RUN apk add --no-cache curl openssl libc6-compat postgresql-client


COPY package.json package-lock.json* ./


RUN npm ci --only=production && npm cache clean --force


COPY prisma ./prisma


RUN npx prisma generate


COPY --from=builder /app/dist ./dist


RUN addgroup -g 1001 -S nodejs && \
    adduser -S nodejs -u 1001


RUN chown -R nodejs:nodejs /app
USER nodejs


EXPOSE 3000


HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:3000/health || exit 1


CMD ["node", "dist/server.js"]

