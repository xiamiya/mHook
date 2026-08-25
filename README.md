# mHook

基于 Xposed / LSPosed 的 Android 应用分析与 Hook 辅助工具。

## 功能特性

- 自定义 Hook 配置：运行期对指定类指定方法做返回值替换，无需修改安装包
- 应用行为控制：分析并控制目标应用的操作行为
- MK 热修复：模式 2（dex 合并热修复）补丁包
- 内存脱壳：纯 Java 内存脱壳，dump 加固后的 dex；按 DexFile cookie 精确定位读取（参考 BlackDex），可对抗部分反作弊加固
- 沙箱脱壳（免 root）：内置 BlackBox 虚拟沙箱引擎，选 APK 免重打包直接运行原始 APK（规避签名/完整性校验），静默运行并自动 dump dex，自动打包 zip 导出到 Download；FART 式主动加载全部类触发补码回收，多根 ClassLoader 枚举 + /proc/self/maps 内存兜底扫描；自动识别加固方案（360/梆梆/爱加密/网易易盾等 40+ 种），支持 32 位应用，脱壳结束自动卸载沙箱应用释放空间
- AI 逆向辅助：
  - 接入大模型，自动分析目标应用并生成 Hook 配置
  - 内置逆向技能库（SKILL.md，含 MT 管理器 MCP 技能）
  - MCP 工具后端：MT 管理器 / 玄星逆核 / ProxyPin
  - AI 自动改包：调用 MT 管理器 MCP 直接定位、修改并构建签名 APK
- XP 模块分析（AI 版）：选择任意 XP 模块 APK，设备端 dexlib2 提取 hook 点，调用 AI 生成多应用 Hook 配置并一键导入，实时显示分析过程；对字符串加密/控制流混淆的模块自动识别并提示
- 检查更新：接入 GitHub Releases API，启动自动检测 + 手动检查，发现新版弹窗展示更新日志并跳转浏览器下载
- 打赏支持：内置支付宝 / 微信收款码界面

## 构建

要求：Android SDK、JDK 11+。

```
gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

Release 签名请自行配置 `signingConfigs` 或使用外部签名工具。

## 开源致谢

本应用基于多个开源项目构建，详见应用内「设置 → 关于 → 感谢开源项目」。
