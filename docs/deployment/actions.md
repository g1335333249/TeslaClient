# 手动构建 GitHub Actions

仓库包含两个独立工作流：`Backend build` 和 `iOS unsigned IPA`。它们只配置 `workflow_dispatch`，推送和 Pull Request 都不会自动触发构建。

在 GitHub 仓库的 **Actions** 页面选择工作流，点击 **Run workflow**：

1. `git_ref` 填写要构建的分支、标签或提交 SHA。默认 `main`。
2. `expected_version` 可选；填写后必须与所选源码中的 Maven 版本或 iOS `MARKETING_VERSION` 一致，否则构建失败。留空则使用源码中的版本。版本不是在 CI 中临时改写的。
3. iOS 的 `run_tests` 默认为 `false`。需要 Simulator 单元测试时手动选中。

后端工作流使用 Java 21 执行 `mvn clean verify`，运行 Testcontainers MySQL 集成测试，再验证 Docker 镜像能构建，并上传对应版本的 Spring Boot JAR。测试失败时不会上传构建产物。

iOS 工作流使用 Xcode 16.4 和 XcodeGen，针对 **iPhoneOS 设备平台**进行 Release archive；它关闭代码签名，将 `Payload/VoltMate.app` 打包成 `VoltMate-<Version>+<Build>-unsigned.ipa`，检查 ZIP 内容和签名状态后上传。未签名 IPA 不能直接安装到普通真机；分发或安装前需用自己的 Apple 证书和描述文件签名。工作流不需要付费开发者账号。启用 `run_tests` 后会在 IPA 上传完成后运行 iOS Simulator 测试。

本地生成同样的 IPA：`bash scripts/build-ios-unsigned-ipa.sh dist/ios`。产物位于 `dist/ios/`，不纳入 Git。当前代码的两份工作流需进入远程默认分支后才会在 GitHub Actions 页面显示；本次修改未自动推送。
