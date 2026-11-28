# Glass-Box Agent Studio 多阶段镜像：前端静态产物 → 可执行 Fat JAR → 最小 JRE 运行。
# 本文件绝不包含任何密钥；config/ 与 data/ 由 .dockerignore 排除，运行期只通过卷挂载。

# ---------- Stage 1：构建前端静态资源 ----------
FROM node:20-alpine AS frontend
WORKDIR /workspace/frontend/user-workbench
# 先只拷贝依赖清单，命中 Docker 层缓存后再拷贝源码。
COPY frontend/user-workbench/package.json frontend/user-workbench/package-lock.json ./
RUN npm ci
COPY frontend/user-workbench/ ./
RUN npm run build

# ---------- Stage 2：产出可执行 Fat JAR ----------
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /workspace
COPY pom.xml ./
COPY backend/ ./backend/
# pom.xml 把 frontend/user-workbench/dist 作为静态资源打进 jar，因此必须先放入前端产物。
COPY --from=frontend /workspace/frontend/user-workbench/dist ./frontend/user-workbench/dist
RUN mvn -B -DskipTests package

# ---------- Stage 3：最小运行镜像 ----------
FROM eclipse-temurin:21-jre-alpine
# 以非 root 用户运行；运行期数据目录用卷挂载，镜像内不含任何本地数据或密钥。
RUN addgroup -S workbench && adduser -S -G workbench workbench
WORKDIR /app
COPY --from=backend /workspace/target/agent-workbench-*.jar /app/app.jar
RUN mkdir -p /app/data && chown -R workbench:workbench /app
USER workbench
VOLUME ["/app/data"]
EXPOSE 8088
# 容器内监听 0.0.0.0 以便宿主机端口映射可达；宿主机启动建议绑定 127.0.0.1:8088:8088，
# 且应用侧 LocalOriginFilter 仍强制校验 Host 与 Origin。
ENV SERVER_ADDRESS="0.0.0.0"
ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
