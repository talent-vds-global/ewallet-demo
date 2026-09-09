# Frontend tinh - khong build step, khong Node. Chi nginx phuc vu file.
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY index.html styles.css app.js /usr/share/nginx/html/
EXPOSE 80
