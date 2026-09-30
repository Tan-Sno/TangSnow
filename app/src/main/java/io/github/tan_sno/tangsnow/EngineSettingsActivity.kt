package io.github.tan_sno.tangsnow

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import io.github.tan_sno.tangsnow.data.PreferenceStore
import io.github.tan_sno.tangsnow.data.SearchEngine
import io.github.tan_sno.tangsnow.data.SearchEngines
import io.github.tan_sno.tangsnow.databinding.ActivityEngineSettingsBinding
import io.github.tan_sno.tangsnow.databinding.ItemEngineBinding
import io.github.tan_sno.tangsnow.util.dp

/** 搜索引擎管理：选择默认引擎 + 添加 / 长按删除自定义引擎 */
class EngineSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEngineSettingsBinding

    /** 本页展示中的对话框（新增引擎 / 删除确认）。见 DialogTracker 的类注释 */
    private val dialogs = io.github.tan_sno.tangsnow.ui.DialogTracker()

    override fun onDestroy() {
        dialogs.cancelAll()
        super.onDestroy()
    }
    private lateinit var prefs: PreferenceStore

    private val engineList: List<SearchEngine>
        get() = SearchEngines.all(prefs)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = PreferenceStore(this)
        binding = ActivityEngineSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddEngine.setOnClickListener { showAddDialog() }

        binding.engineList.onItemClickListener =
            android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
                val engine = engineList[position]
                prefs.searchEngineId = engine.id
                refreshList()
                // 走本地化名称：内置引擎的 label 固定为中文，切到英文界面时不应冒出中文
                toast(SearchEngines.localizedLabel(this, engine))
            }

        // 长按自定义引擎删除
        binding.engineList.onItemLongClickListener =
            android.widget.AdapterView.OnItemLongClickListener { _, _, position, _ ->
                val engine = engineList[position]
                if (engine.isCustom) {
                    confirmDelete(engine)
                    true
                } else {
                    false
                }
            }

        refreshList()
    }

    private fun refreshList() {
        binding.engineList.adapter = EngineAdapter(engineList)
        // 当前默认引擎的对勾由 EngineAdapter 按 engine.id 自行渲染（imgCheck），
        // 不走 ListView 的 choiceMode/checkState，无需（也无法）调用 setItemChecked。
    }

    private fun showAddDialog() {
        val nameInput = EditText(this).apply { hint = getString(R.string.engine_name_hint) }
        val urlInput = EditText(this).apply {
            hint = getString(R.string.engine_url_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }

        val body = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            // 原先是原始像素（48/8），在不同密度屏上视觉差别极大；按 3x 屏的口径折算为 dp
            // （48px@3x = 16dp、8px@3x ≈ 2.7dp → 3dp），与全项目统一的 dp() 写法对齐
            setPadding(dp(16), dp(3), dp(16), dp(3))
            addView(nameInput)
            addView(urlInput)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.engine_add_title)
            .setView(body)
            .setNegativeButton(R.string.dlg_cancel, null)
            .setPositiveButton(R.string.dlg_ok) { _, _ ->
                val name = nameInput.text.toString()
                val template = urlInput.text.toString()
                // 逐个原因给对应文案：`addCustomEngine` 用一个 false 表达四种失败，一律报
                // 「该名称或地址已存在」会把「名称为空」「已达上限」也指成重复 —— 用户照着提示改不到点上。
                val ok = when {
                    name.isBlank() -> {
                        toast(R.string.engine_name_empty)
                        false
                    }
                    !PreferenceStore.isHttpTemplate(template) -> {
                        toast(R.string.engine_url_scheme_invalid)
                        false
                    }
                    prefs.customEngines.size >= PreferenceStore.MAX_CUSTOM_ENGINES -> {
                        val max = PreferenceStore.MAX_CUSTOM_ENGINES
                        toast(resources.getQuantityString(R.plurals.engine_limit_reached, max, max))
                        false
                    }
                    !prefs.addCustomEngine(name, template) -> {
                        toast(R.string.engine_duplicated)
                        false
                    }
                    else -> true
                }
                if (ok) refreshList()
            }
            .create()
            .let { dialogs.track(it) }
    }

    private fun confirmDelete(engine: SearchEngine) {
        val label = SearchEngines.localizedLabel(this, engine)
        AlertDialog.Builder(this)
            .setTitle(label)
            .setMessage(getString(R.string.engine_delete_confirm, label))
            .setNegativeButton(R.string.dlg_cancel, null)
            .setPositiveButton(R.string.dlg_ok) { _, _ ->
                val index = engineList.indexOfFirst { it.id == engine.id }
                val customIndex = index - SearchEngines.builtins.size
                if (customIndex >= 0) {
                    prefs.removeCustomEngine(customIndex)
                    refreshList()
                }
            }
            .create()
            .let { dialogs.track(it) }
    }

    private inner class EngineAdapter(private val data: List<SearchEngine>) : BaseAdapter() {
        override fun getCount(): Int = data.size
        override fun getItem(position: Int): Any = data[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val bindingRow =
                if (convertView == null) {
                    ItemEngineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                } else {
                    ItemEngineBinding.bind(convertView)
                }
            val engine = data[position]
            bindingRow.txtEngine.text = if (engine.isCustom) {
                getString(
                    R.string.engine_custom_suffix_format,
                    SearchEngines.localizedLabel(this@EngineSettingsActivity, engine)
                )
            } else {
                SearchEngines.localizedLabel(this@EngineSettingsActivity, engine)
            }
            bindingRow.imgCheck.isVisible = engine.id == prefs.searchEngineId
            return bindingRow.root
        }
    }
}