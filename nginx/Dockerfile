# The edge: TLS, rate limits and security headers in front of the backend and the SPA.
#
# Its own image rather than stock nginx with a mounted config because it has to decide, at
# start and then every minute, which certificate to serve — see docker-entrypoint.d — and that
# needs openssl for the temporary self-signed one.
FROM nginx:1.27-alpine
RUN apk add --no-cache openssl && rm -f /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/nginx.conf
COPY templates/ /etc/nginx/templates/
COPY docker-entrypoint.d/ /docker-entrypoint.d/
COPY cpintel-cert.sh /usr/local/bin/cpintel-cert
RUN chmod +x /docker-entrypoint.d/40-cpintel-tls.sh /usr/local/bin/cpintel-cert \
 && mkdir -p /var/www/certbot /etc/nginx/tls /etc/nginx/certs
EXPOSE 80 443
