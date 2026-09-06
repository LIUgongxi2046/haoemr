FROM node:24.13.0-bookworm-slim
RUN groupadd --system --gid 10001 haoemr \
    && useradd --system --uid 10001 --gid haoemr --home-dir /opt/haoemr haoemr
WORKDIR /opt/haoemr/web
COPY --chown=haoemr:haoemr web/package.json web/package-lock.json ./
RUN npm ci --ignore-scripts && npm cache clean --force
COPY --chown=haoemr:haoemr web/ ./
USER 10001:10001
EXPOSE 4177
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]
