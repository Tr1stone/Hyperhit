package com.example.hyperhit

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.hyperhit.automation.*
import com.example.hyperhit.ui.theme.HyperhitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HyperhitTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    HyperhitScreen(Modifier.fillMaxSize().padding(padding))
                }
            }
        }
    }
}

@Composable
fun HyperhitScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { AutomationStore.get(context) }
    val config by store.state.collectAsState()
    val runtime by AutomationRuntime.state.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    var selectedName by rememberSaveable { mutableStateOf(Mode.TAP.name) }
    val mode = Mode.valueOf(selectedName)
    var showTest by rememberSaveable { mutableStateOf(false) }
    var tapInterval by rememberSaveable(config.tap.intervalMs) { mutableStateOf(config.tap.intervalMs.toString()) }
    var tapRounds by rememberSaveable(config.tap.rounds) { mutableStateOf(config.tap.rounds.toString()) }
    var swipeDuration by rememberSaveable(config.swipe.durationMs) { mutableStateOf(config.swipe.durationMs.toString()) }
    var swipeGap by rememberSaveable(config.swipe.gapMs) { mutableStateOf(config.swipe.gapMs.toString()) }
    fun message(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    fun exitTest() { AutoClickService.instance?.stop(); showTest = false }
    BackHandler(showTest) { exitTest() }
    if (showTest) {
        TestArea(modifier, ::exitTest)
        return
    }
    val busy = runtime.loop.mode == mode && runtime.loop.phase in listOf(Phase.RUNNING, Phase.PAUSING)
    Column(
        modifier = modifier.imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Hyperhit", style = MaterialTheme.typography.headlineLarge)
        Text(if (runtime.connected) "无障碍服务已连接" else "请先开启 Hyperhit 无障碍服务")
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
            Text("打开无障碍设置")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = mode == Mode.TAP, onClick = { selectedName = Mode.TAP.name }, label = { Text("连点") })
            FilterChip(selected = mode == Mode.SWIPE, onClick = { selectedName = Mode.SWIPE.name }, label = { Text("循环滑动") })
        }
        if (mode == Mode.TAP) {
            Text("按顺序点击已选位置", style = MaterialTheme.typography.titleMedium)
            Text("单点用于固定位置连点；多个点按添加顺序执行一轮。")
            NumberSetting(tapInterval, { tapInterval = it }, "点击间隔（毫秒）", "100～60000，每两个点击开始之间的间隔", !busy)
            NumberSetting(tapRounds, { tapRounds = it }, "循环轮数", "1～10000，全部点击点执行完算一轮", !busy)
            if (config.tap.points.isEmpty()) Text("尚未选点：显示悬浮控制后，切到目标页面，点“添加点”。")
            config.tap.points.forEachIndexed { index, point -> Text("${index + 1}. ${point.label()}") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy && config.tap.points.isNotEmpty(), onClick = {
                    if (runtime.loop.mode == Mode.TAP) AutoClickService.instance?.stop()
                    store.removeLastTap()
                }) { Text("移除最后一点") }
                TextButton(enabled = !busy && config.tap.points.isNotEmpty(), onClick = {
                    if (runtime.loop.mode == Mode.TAP) AutoClickService.instance?.stop()
                    store.clear(Mode.TAP)
                }) { Text("清空点击点") }
            }
        } else {
            Text("持续重复 A → B", style = MaterialTheme.typography.titleMedium)
            Text("不设置重复次数；开始后持续滑动，使用悬浮按钮暂停或继续。")
            NumberSetting(swipeDuration, { swipeDuration = it }, "滑动时长（毫秒）", "100～10000，从 A 滑到 B 花费的时间", !busy)
            NumberSetting(swipeGap, { swipeGap = it }, "滑动间隔（毫秒）", "100～60000，每次到达 B 后等待的时间", !busy)
            Text(config.swipe.start?.let { "A：${it.label()}" } ?: "A：尚未选择")
            Text(config.swipe.end?.let { "B：${it.label()}" } ?: "B：尚未选择")
            Text("选路径时从 A 按住拖到 B，按下及拖动会实时显示坐标。")
            TextButton(enabled = !busy && config.swipe.start != null, onClick = {
                if (runtime.loop.mode == Mode.SWIPE) AutoClickService.instance?.stop()
                store.clear(Mode.SWIPE)
            }) { Text("清空滑动路径") }
        }
        Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            runCatching {
                if (mode == Mode.TAP) {
                    store.saveTapSettings(
                        requireNotNull(tapInterval.trim().toLongOrNull()) { "请输入有效的点击间隔" },
                        requireNotNull(tapRounds.trim().toIntOrNull()) { "请输入有效的连点轮数" }
                    )
                } else {
                    store.saveSwipeSettings(
                        requireNotNull(swipeDuration.trim().toLongOrNull()) { "请输入有效的滑动时长" },
                        requireNotNull(swipeGap.trim().toLongOrNull()) { "请输入有效的滑动间隔" }
                    )
                }
                val service = requireNotNull(AutoClickService.instance) { "设置已保存，请先开启无障碍服务" }
                keyboard?.hide()
                service.showControls(mode)
            }.onFailure { message(it.message ?: "无法保存设置") }
        }) { Text("保存并显示${if (mode == Mode.TAP) "连点" else "滑动"}悬浮控制") }
        Text("控制条顶部可拖动。选点只记录位置；退出选点后，目标 App 恢复正常触摸。位置和参数会保存在本机。")
        if (runtime.loop.phase != Phase.IDLE || runtime.loop.problem != null) {
            Text(runtime.loop.problem ?: when (runtime.loop.phase) {
                Phase.RUNNING -> "${if (runtime.loop.mode == Mode.TAP) "连点" else "滑动"}正在运行"
                Phase.PAUSING -> "当前动作结束后暂停"
                Phase.PAUSED -> "已暂停，可在悬浮控制中继续"
                Phase.COMPLETED -> "连点任务已完成，共 ${runtime.loop.completed} 次点击"
                Phase.IDLE -> "待开始"
            })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { AutoClickService.instance?.stop(); showTest = true }) { Text("进入测试区") }
            TextButton(onClick = { AutoClickService.instance?.stop() }) { Text("结束当前任务") }
        }
        Text("屏幕方向或尺寸变化后需重新选点；锁屏会结束任务。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NumberSetting(value: String, onChange: (String) -> Unit, label: String, hint: String, enabled: Boolean) {
    OutlinedTextField(
        value = value, onValueChange = { if (it.length <= 10) onChange(it) },
        label = { Text(label) }, supportingText = { Text(hint) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun TestArea(modifier: Modifier, onBack: () -> Unit) {
    var clicks by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Hyperhit · 测试区", style = MaterialTheme.typography.headlineSmall)
        OutlinedButton(onClick = onBack) { Text("返回设置并结束任务") }
        Button(onClick = { clicks++ }, modifier = Modifier.fillMaxWidth()) {
            Text("测试目标：已收到 $clicks 次点击")
        }
        Text("连点：在悬浮面板添加上方目标按钮的位置。\n滑动：在下方列表从 A 拖到 B 保存路径，再开始。")
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items((1..100).toList()) { index ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text("滑动测试 · 第 $index 行", Modifier.padding(24.dp))
                }
            }
        }
    }
}
