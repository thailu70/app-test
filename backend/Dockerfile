# Production Dockerfile for Transport Navigator VPS Backend
FROM node:22-alpine

# Set working directory
WORKDIR /app

# Install system dependencies (curl for healthcheck)
RUN apk add --no-cache curl

# Install dependencies first for optimal Docker layer caching
COPY package.json package-lock.json ./
RUN npm ci --only=production

# Copy application source code
COPY src/ ./src/

# Create persistent data directory for SQLite database
RUN mkdir -p /app/data && chown -R node:node /app

# Switch to non-root unprivileged user for security
USER node

# Environment defaults
ENV NODE_ENV=production \
    PORT=3000 \
    HOST=0.0.0.0 \
    DATA_DIR=/app/data

# Expose HTTP and WebSocket port
EXPOSE 3000

# Docker Healthcheck
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:3000/api/health || exit 1

# Start server
CMD ["node", "src/server.js"]
