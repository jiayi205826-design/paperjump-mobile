# PaperJump Mobile PWA

这是 PaperJump 已有手机原型的多页面版本，不是原生 APK。通过 HTTPS 打开后，
可以安装到 Android 主屏幕，并使用手机后置摄像头。

## 当前能力

- 识别 `pages.json` 中带四个 `markers` 的所有 ArUco 页面。
- 根据识别出的 `page_id` 加载 `custom_nodes.json` 中相同页面的框选节点。
- 使用 MediaPipe Hand Landmarker 获取食指指尖。
- 指尖命中节点后，经确认打开网页、B站、小红书或其他 HTTP(S) 链接。
- 支持导入电脑端最新的 `pages.json` 和 `custom_nodes.json`，并保存在手机浏览器本地。
- Windows 本地文件不会显示，因为 Android 无法打开 `D:\...` 路径。

Natural Page21～24 当前不会在手机端识别；PWA 只加载有 `markers` 字段的页面。

## 数据更新

PWA 目录内包含构建时的默认快照。电脑端节点变化后，可以在手机页面中分别导入：

- `D:\paper_jump\pages.json`
- `D:\paper_jump\custom_nodes.json`

以后可把这一步替换为局域网或云同步，不需要改变页面/节点的数据结构。

## 安装条件

手机摄像头 API 要求 HTTPS。把整个 `PWA` 目录部署到 GitHub Pages、Cloudflare
Pages、Netlify 等 HTTPS 静态托管，手机访问后使用浏览器的“添加到主屏幕”。

首次运行需要网络下载 js-aruco2、MediaPipe WASM 和手部模型；之后应用外壳会缓存，
但当前版本还不是完全离线包。
