package com.github.jing332.tts_server_android.ui.systts.edit

import android.content.Context
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jing332.tts_server_android.App
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.bean.AzureVoiceBean
import com.github.jing332.tts_server_android.bean.CreationVoiceBean
import com.github.jing332.tts_server_android.bean.EdgeVoiceBean
import com.github.jing332.tts_server_android.constant.CnLocalMap
import com.github.jing332.tts_server_android.constant.TtsApiType
import com.github.jing332.tts_server_android.data.entities.SysTts
import com.github.jing332.tts_server_android.model.tts.ExpressAs
import com.github.jing332.tts_server_android.model.tts.MsTTS
import com.github.jing332.tts_server_android.model.tts.MsTtsAudioFormat
import com.github.jing332.tts_server_android.model.tts.MsTtsFormatManger
import com.github.jing332.tts_server_android.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import tts_server_lib.Tts_server_lib
import java.io.File

class MsTtsEditViewModel : ViewModel() {
    companion object {
        const val TAG = "TtsSettingsViewModel"
    }

    val displayNameLiveData: MutableLiveData<String> by lazy { MutableLiveData() }
    val readAloudTargetLiveData: MutableLiveData<SpinnerData> by lazy { MutableLiveData() }
    val apiLiveData: MutableLiveData<SpinnerData> by lazy { MutableLiveData() }
    val languageLiveData: MutableLiveData<SpinnerData> by lazy { MutableLiveData() }
    val voiceLiveData: MutableLiveData<SpinnerData> by lazy { MutableLiveData() }
    val voiceStyleLiveData: MutableLiveData<SpinnerData> by lazy { MutableLiveData() }
    val voiceRoleLiveData: MutableLiveData<SpinnerData> by lazy { MutableLiveData() }
    val audioFormatLiveData: MutableLiveData<SpinnerData> by lazy { MutableLiveData() }

    val rateLiveData: MutableLiveData<Int> by lazy { MutableLiveData() }
    val volumeLiveData: MutableLiveData<Int> by lazy { MutableLiveData() }

    val voiceStyleDegreeLiveData: MutableLiveData<Float> by lazy { MutableLiveData() }

    //    private lateinit var mVoiceProperty: SysTtsConfigItem
    lateinit var mSysTts: SysTts
    private val mMsTtsProperty: MsTTS
        inline get() {
            return mSysTts.ttsAs<MsTTS>()
        }

    private val mJson
        inline get() = App.jsonBuilder

    private var mCacheDir: String = ""
    private lateinit var mEdgeVoices: List<EdgeVoiceBean>
    private lateinit var mAzureVoices: List<AzureVoiceBean>
    private lateinit var mCreationVoices: List<CreationVoiceBean>

    fun getData(inputDisplayName: String): SysTts {
        val voice = voiceLiveData.value?.selected()?.displayName.toString()
        mSysTts.displayName = inputDisplayName.ifBlank { voice }
        return mSysTts
    }

    fun loadData(context: Context, data: SysTts) {
        Log.d(TAG, "loadData")
        this.mSysTts = data

        val apiListData = arrayListOf<SpinnerItemData>()
        context.apply {
            arrayOf(
                getString(R.string.api_edge),
                getString(R.string.api_azure),
                getString(R.string.api_creation)
            ).forEach {
                apiListData.add(SpinnerItemData(it, ""))
            }
        }

        displayNameLiveData.value = mSysTts.displayName
        apiLiveData.value = SpinnerData(apiListData, mMsTtsProperty.api)

        val readAloudTargetList = arrayListOf<SpinnerItemData>()
        context.apply {
            arrayOf(
                getString(R.string.ra_global),
                getString(R.string.ra_aside),
                getString(R.string.ra_dialogue)
            ).forEach {
                readAloudTargetList.add(SpinnerItemData(it, ""))
            }
        }

        readAloudTargetLiveData.value =
            SpinnerData(readAloudTargetList, mSysTts.readAloudTarget)

        mMsTtsProperty.apply {
            voiceStyleDegreeLiveData.value = expressAs?.styleDegree ?: 1F
            volumeLiveData.value = prosody.volume
            rateLiveData.value = prosody.rate
        }
        mCacheDir = context.cacheDir.path
    }

    fun onReadAloudTargetSelected(position: Int) {
        Log.d(TAG, "onReadAloudTargetSelected: $position")
        mSysTts.readAloudTarget = position
    }

    /* {接口}选中变更 */
    fun apiSelected(position: Int, finally: (String?) -> Unit) {
        Log.d(TAG, "apiSelected: $position")
        mMsTtsProperty.api = position
        apiLiveData.value?.position = position
        updateFormatLiveData()
        viewModelScope.launch(Dispatchers.IO) {
            kotlin.runCatching {
                when (position) {
                    TtsApiType.EDGE -> useEdgeApi()
                    TtsApiType.AZURE -> useAzureApi()
                    TtsApiType.CREATION -> useCreationApi()
                }
            }.onFailure {
                finally.invoke(it.message)
            }.onSuccess {
                finally.invoke(null)
            }
        }
    }

    /* {语言}选中已变更, 更新声音列表*/
    fun languageSelected(position: Int) {
        Log.d(TAG, "languageSelected: $position")
        languageLiveData.value?.position = position
        mMsTtsProperty.locale = languageLiveData.value!!.list[position].value
        val tmpVoiceList = arrayListOf<SpinnerItemData>()
        when (mMsTtsProperty.api) {
            TtsApiType.EDGE -> {
                mEdgeVoices.forEach { item ->
                    if (item.locale == languageLiveData.value!!.list[position].value)
                        tmpVoiceList.add(
                            SpinnerItemData(
                                CnLocalMap.getEdgeVoice(item.shortName) + "（${item.shortName}）",
                                item.shortName
                            )
                        )
                }
            }
            TtsApiType.AZURE -> {
                mAzureVoices.forEach {
                    if (it.locale == mMsTtsProperty.locale)
                        tmpVoiceList.add(
                            SpinnerItemData(
                                it.localName + "（${it.shortName}）",
                                it.shortName
                            )
                        )
                }
            }
            TtsApiType.CREATION -> {
                mCreationVoices.forEach {
                    if (it.locale == languageLiveData.value!!.list[position].value)
                        tmpVoiceList.add(
                            SpinnerItemData(
                                it.properties.localName + "（${it.shortName}）",
                                it.shortName
                            )
                        )
                }
            }
        }
        tmpVoiceList.sortBy { it.displayName }
        var selectedPos = 0
        tmpVoiceList.forEachIndexed { index, itemData ->
            if (itemData.value == mMsTtsProperty.voiceName) {
                selectedPos = index
            }
        }
        voiceLiveData.value = SpinnerData(tmpVoiceList, selectedPos)
    }

    /* {声音}选中已变更，更新风格和角色*/
    fun voiceSelected(position: Int) {
        voiceLiveData.value?.also {
            it.position = position
            mMsTtsProperty.voiceName = it.list[position].value
        }
        Log.d(TAG, "voiceSelected ${mMsTtsProperty.voiceName}")

        when (mMsTtsProperty.api) {
            TtsApiType.AZURE -> {
                mAzureVoices.forEach { voiceItem ->
                    if (mMsTtsProperty.voiceName == voiceItem.shortName) {
                        /* 风格 */
                        val styleList =
                            arrayListOf(SpinnerItemData(App.instance.getString(R.string.none), ""))
                        var selectedStyle = 0
                        voiceItem.styleList?.let {
                            styleList[0] =
                                SpinnerItemData(App.instance.getString(R.string.default_str), "")
                            voiceItem.styleList.forEachIndexed { index, styleName ->
                                styleList.add(
                                    SpinnerItemData(
                                        CnLocalMap.getStyleAndRole(styleName),
                                        styleName
                                    )
                                )
                                if (mMsTtsProperty.expressAs?.style == styleName)
                                    selectedStyle = index + 1
                            }
                        }
                        voiceStyleLiveData.value = SpinnerData(styleList, selectedStyle)

                        /* 角色 */
                        val roleList = arrayListOf(SpinnerItemData("无", ""))
                        var selectedRole = 0
                        voiceItem.rolePlayList?.let {
                            roleList[0] = SpinnerItemData("默认", "")
                            voiceItem.rolePlayList.forEachIndexed { index, roleName ->
                                roleList.add(
                                    SpinnerItemData(
                                        CnLocalMap.getStyleAndRole(roleName),
                                        roleName
                                    )
                                )
                                if (mMsTtsProperty.expressAs?.role == roleName)
                                    selectedRole = index + 1
                            }
                        }
                        voiceRoleLiveData.value = SpinnerData(roleList, selectedRole)
                        return
                    }
                }
            }
            TtsApiType.CREATION -> {
                mCreationVoices.forEach { voiceItem ->
                    if (mMsTtsProperty.voiceName == voiceItem.shortName) {
                        mMsTtsProperty.voiceId = voiceItem.id
                        /* 风格 */
                        val styleList =
                            arrayListOf(SpinnerItemData(App.instance.getString(R.string.none), ""))
                        var selectedStyle = 0
                        if (voiceItem.properties.voiceStyleNames.isNotBlank())
                            voiceItem.properties.voiceStyleNames.split(",").apply {
                                styleList[0] = SpinnerItemData(
                                    App.instance.getString(R.string.default_str),
                                    ""
                                )
                                forEachIndexed { index, styleName ->
                                    if (styleName != "Default")
                                        styleList.add(
                                            SpinnerItemData(
                                                CnLocalMap.getStyleAndRole(styleName),
                                                styleName
                                            )
                                        )
                                    if (mMsTtsProperty.expressAs?.style == styleName)
                                        selectedStyle = index + 1
                                }
                            }

                        voiceStyleLiveData.value = SpinnerData(styleList, selectedStyle)

                        /* 角色 */
                        val roleList =
                            arrayListOf(SpinnerItemData(App.instance.getString(R.string.none), ""))
                        var selectedRole = 0
                        if (voiceItem.properties.voiceRoleNames.isNotBlank())
                            voiceItem.properties.voiceRoleNames.split(",").apply {
                                roleList[0] = SpinnerItemData(
                                    App.instance.getString(R.string.default_str),
                                    ""
                                )
                                forEachIndexed { index, roleName ->
                                    if (roleName != "Default")
                                        roleList.add(
                                            SpinnerItemData(
                                                CnLocalMap.getStyleAndRole(roleName),
                                                roleName
                                            )
                                        )
                                    if (mMsTtsProperty.expressAs?.role == roleName)
                                        selectedRole = index + 1

                                }
                            }
                        voiceRoleLiveData.value = SpinnerData(roleList, selectedRole)
                        return
                    }
                }
            }
            TtsApiType.EDGE -> {}
        }
    }

    fun formatSelected(position: Int): Boolean {
        val value = audioFormatLiveData.value!!.list[position].displayName
        mMsTtsProperty.format = value
        Log.d(TAG, "formatSelected $value")
        if (value.contains("raw")) return true
        return false
    }

    fun volumeChanged(volume: Int) {
        mMsTtsProperty.prosody.volume = volume
    }

    fun rateChanged(rate: Int) {
        mMsTtsProperty.prosody.rate = rate
    }

    fun onStyleDegreeChanged(degree: Float) {
        if (mMsTtsProperty.api == TtsApiType.EDGE) return

        if (mMsTtsProperty.expressAs == null) mMsTtsProperty.expressAs = ExpressAs()
        mMsTtsProperty.expressAs?.styleDegree = degree
    }

    private fun useEdgeApi() {
        mMsTtsProperty.voiceId = null
        mMsTtsProperty.expressAs = null

        if (!this::mEdgeVoices.isInitialized) {
            /* 使用本地缓存或远程下载 */
            val cachePath = "$mCacheDir/edge/voices.json"
            val data: ByteArray
            if (FileUtils.fileExists(cachePath)) {
                data = File(cachePath).readBytes()
            } else {
                data = Tts_server_lib.getEdgeVoices()
                FileUtils.saveFile(cachePath, data)
            }
            mEdgeVoices = mJson.decodeFromString(data.decodeToString())
        }

        val tmpLangList = arrayListOf<SpinnerItemData>()
        mEdgeVoices.forEach { item ->
            for (it in tmpLangList)
                if (it.value == item.locale) return@forEach

            tmpLangList.add(SpinnerItemData(CnLocalMap.getLanguage(item.locale), item.locale))
        }
        tmpLangList.sortBy { it.value }
        var selected = 0
        tmpLangList.forEachIndexed { index, item ->
            if (mMsTtsProperty.locale == item.value)
                selected = index
        }
        languageLiveData.postValue(SpinnerData(tmpLangList, selected))
        /* Edge接口不支持风格和角色，故设为无 */
        voiceStyleLiveData.postValue(SpinnerData(arrayListOf(SpinnerItemData("无", "")), 0))
        voiceRoleLiveData.postValue(SpinnerData(arrayListOf(SpinnerItemData("无", "")), 0))
    }

    private fun useAzureApi() {
        val cacheFilepath = "$mCacheDir/azure/voices.json"
        val data: ByteArray
        if (FileUtils.fileExists(cacheFilepath)) {
            data = File(cacheFilepath).readBytes()
        } else {
            data = Tts_server_lib.getAzureVoice()
            FileUtils.saveFile(cacheFilepath, data)
        }

        mAzureVoices = mJson.decodeFromString(data.decodeToString())
        val languageList = arrayListOf<String>()
        mAzureVoices.forEach {
            if (!languageList.contains(it.locale)) {
                languageList.add(it.locale)
            }
        }
        languageList.sort()

        val dataList = arrayListOf<SpinnerItemData>()
        var selected = 0
        languageList.forEachIndexed { i, v ->
            if (mMsTtsProperty.locale == v) { /* 选中配置文件中的位置 */
                selected = i
            }
            dataList.add(SpinnerItemData(CnLocalMap.getLanguage(v), v))
        }
        languageLiveData.postValue(SpinnerData(dataList, selected))
    }

    private fun useCreationApi() {
        val cacheFilepath = "$mCacheDir/creation/voices.json"
        val data: ByteArray
        if (FileUtils.fileExists(cacheFilepath)) {
            data = File(cacheFilepath).readBytes()
        } else {
            data = Tts_server_lib.getCreationVoices()
            FileUtils.saveFile(cacheFilepath, data)
        }
        mCreationVoices =
            mJson.decodeFromString(data.decodeToString())
        val tmpLanguageList = arrayListOf<String>()
        mCreationVoices.forEach {
            if (!tmpLanguageList.contains(it.locale)) {
                tmpLanguageList.add(it.locale)
            }
        }
        tmpLanguageList.sort()

        val dataList = arrayListOf<SpinnerItemData>()
        var selected = 0
        tmpLanguageList.forEachIndexed { i, v ->
            if (mMsTtsProperty.locale == v) { /* 选中配置文件中的位置 */
                selected = i
            }
            dataList.add(SpinnerItemData(CnLocalMap.getLanguage(v), v))
        }
        languageLiveData.postValue(SpinnerData(dataList, selected))
    }

    /* 根据API更新音频格式 */
    private fun updateFormatLiveData() {
        val api = when (mMsTtsProperty.api) {
            TtsApiType.EDGE -> {
                MsTtsAudioFormat.SupportedApi.EDGE
            }
            TtsApiType.AZURE -> {
                MsTtsAudioFormat.SupportedApi.AZURE
            }
            else -> MsTtsAudioFormat.SupportedApi.CREATION //2
        }
        val formats = MsTtsFormatManger.getFormatsBySupportedApi(api)
        var selected = 0
        val tmpFormats = arrayListOf<SpinnerItemData>()
        formats.forEachIndexed { index, v ->
            if (mMsTtsProperty.format == v) {
                selected = index
            }
            tmpFormats.add(SpinnerItemData(v, v))
        }
        audioFormatLiveData.postValue(SpinnerData(tmpFormats, selected))
    }

    fun voiceStyleSelected(position: Int) {
        if (mMsTtsProperty.api == TtsApiType.EDGE) return

        voiceStyleLiveData.value?.position = position
        if (mMsTtsProperty.expressAs == null) mMsTtsProperty.expressAs = ExpressAs()
        mMsTtsProperty.expressAs?.style = voiceStyleLiveData.value!!.list[position].value
    }

    fun voiceRoleSelected(position: Int) {
        if (mMsTtsProperty.api == TtsApiType.EDGE) return

        voiceRoleLiveData.value?.position = position
        if (mMsTtsProperty.expressAs == null) mMsTtsProperty.expressAs = ExpressAs()
        mMsTtsProperty.expressAs?.role = voiceRoleLiveData.value!!.list[position].value
    }


    data class SpinnerData(var list: List<SpinnerItemData>, var position: Int) {
        fun selected(): SpinnerItemData {
            return list[position]
        }
    }

    data class SpinnerItemData(var displayName: String, var value: String)
}