# Agentic SDK 的 Maven Central 发布

SDK 使用独立 POM 和 `com.aliyun.odps:agentic-sdk` 坐标。发布只包含 Java SDK，
不构建或发布 Studio、浏览器适配器或示例应用。

## CI 验证

推送 `main`、创建 Pull Request 或手动运行 `Agentic SDK CI` 时，GitHub Actions 会：

1. 在 Java 21 / 25 上运行 SDK 测试。
2. 生成普通 JAR、sources JAR 和 Javadoc JAR。
3. 检查 JAR 中的许可证、提示词资源，以及无父项目依赖的消费者 POM。
4. 编译示例和 README 中的完整 QuickStart。
5. 保存测试报告与构建产物。

版本、依赖与插件配置以 [pom.xml](../pom.xml) 为准。

## 首次配置

发布账号需要在 [Central Portal](https://central.sonatype.com/) 拥有
`com.aliyun.odps` 命名空间的发布权限，并生成 Portal user token。
在 GitHub 仓库的 Actions secrets 中配置以下四项：

| Secret | 内容 |
| --- | --- |
| `CENTRAL_USERNAME` | Portal user token 的 username，不是网页登录用户名 |
| `CENTRAL_PASSWORD` | Portal user token 的 password |
| `GPG_PRIVATE_KEY` | 用于发布签名的 ASCII-armored GPG 私钥 |
| `GPG_PASSPHRASE` | 私钥口令；当前 CI 要求使用带口令的发布密钥 |

签名公钥应发布到 Central 支持的公钥服务器。密钥和 token 不写入源码、POM 或提交历史。
详细要求见 [Central 发布要求](https://central.sonatype.org/publish/requirements/)、
[Portal token](https://central.sonatype.org/publish/generate-portal-token/) 和
[GPG 签名说明](https://central.sonatype.org/publish/requirements/gpg/)。

## 发布版本

先更新 `pom.xml` 和 `examples/pom.xml` 中的 `revision`，提交后创建相同版本的标签。
例如发布 `1.6.9` 时：

```bash
mvn -B -ntp -Prelease-artifacts clean install
python3 scripts/check-release.py --artifacts --tag agentic-sdk-v1.6.9
mvn -B -ntp -f examples/pom.xml package
git tag -a agentic-sdk-v1.6.9 -m 'Agentic SDK 1.6.9'
git push origin agentic-sdk-v1.6.9
```

`Publish Agentic SDK to Maven Central` 会先校验版本标签，再运行两种 JDK 的 CI，
最后签名并上传发布。插件开启 `autoPublish` 并等待 `published` 状态；
只有 Central 确认发布后，发布步骤才报告成功。
也可以手动运行该 workflow，并指定一个已存在的正式版本标签。
标签只接受 `agentic-sdk-vMAJOR.MINOR.PATCH`，不接受 SNAPSHOT、预发布后缀或隐式版本覆盖。

Central 的正式版本不可覆盖。如果发布已成功，不要重跑同一版本的发布任务，
应递增版本；若任务在上传后超时，先在 Portal 核查该次部署的状态。
首次配置 secrets 或命名空间权限之前，可以运行构建 CI，但发布任务会明确失败。

## 本地打包与签名验证

```bash
# 无需发布凭证，生成并验证消费者产物
mvn -B -ntp -Prelease-artifacts clean install
python3 scripts/check-release.py --artifacts

# 已配置本地 GPG 时，生成签名产物，但不上传 Central
mvn -B -ntp -Prelease-artifacts,central-publish \
  -Dcentral.skipPublishing=true -DskipTests clean deploy
python3 scripts/check-release.py --artifacts --signed
```

跳过上传的参数由 POM 显式绑定到插件配置。此模式不需要 Portal token，
只生成签名产物；Central 上传 bundle 由真实发布步骤生成。

本地真实发布同样使用 `-Prelease-artifacts,central-publish deploy`，
在 Maven `settings.xml` 的 `central` server 中提供 Portal token，
通过环境变量 `MAVEN_GPG_PASSPHRASE` 提供口令。

配置依据：[Sonatype Maven 发布插件](https://central.sonatype.org/publish/publish-portal-maven/)、
[Maven GPG Plugin](https://maven.apache.org/plugins/maven-gpg-plugin/sign-mojo.html)、
[GitHub Actions secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。
