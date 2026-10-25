FROM node:20-alpine

WORKDIR /app
COPY package*.json ./
RUN npm install --fetch-timeout=30000 --fetch-retries=1

COPY . .
EXPOSE 5173
CMD ["npm", "run", "dev"]
