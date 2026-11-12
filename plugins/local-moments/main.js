/*
 * 本机朋友圈 · 插件入口
 *
 * 这个文件只提供清单/版本元数据，不注册任何模型工具。
 *
 * 原因：助手的读/写身份必须由宿主 chat/task 上下文创建，不能由页面或
 * QuickJS 参数自证。当前 localData 契约只把可信 actor 交给宿主接线后的
 * WebView 页面（LocalDataActor.User）以及宿主任务（LocalDataActor.Assistant）；
 * 在宿主提供 assistant 作用域的 localData 沙箱桥之前，这里不添加任何
 * 模型读取/发布工具，也不返回任何模拟动态或模拟助手回复。
 *
 * UI 在 ui/index.html，通过 window.OrangeBridge.localData 访问宿主 store。
 */
var MOMENTS_PLUGIN = {
  id: 'com.orangechat.localmoments',
  version: '1.0.2',
  entry: 'ui/index.html'
};

exports.MOMENTS_PLUGIN = MOMENTS_PLUGIN;
