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

/** 搜索引擎管理：选择默认引擎 + 添加 / 长按删除自定义引擎 */
class EngineSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEngineSettingsBinding
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
            setPadding(48, 8, 48, 8)
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
                val ok = when {
                    !PreferenceStore.isHttpTemplate(template) -> {
                        toast(R.string.engine_url_scheme_invalid)
                        false
                    }
                    else -> prefs.addCustomEngine(name, template).also {
                        if (!it) toast(R.string.engine_duplicated)
                    }
                }
                if (ok) refreshList()
            }
            .show()
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
            .show()
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