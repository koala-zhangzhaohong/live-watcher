package com.koala.tiktok.live.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

@JsonIgnoreProperties(ignoreUnknown = true)
data class GiftInfoModel(
    val data: GiftInfoData? = null,
    val extra: JsonNode? = null,
    @JsonProperty("status_code")
    val statusCode: Int = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GiftInfoData(
    @JsonProperty("gifts_info")
    val giftsInfo: GiftsInfo? = null,
    val gifts: List<GiftItem> = emptyList(),
    val pages: List<JsonNode> = emptyList(),
    @JsonProperty("doodle_templates")
    val doodleTemplates: List<JsonNode> = emptyList(),
    @JsonProperty("default_tab_id")
    val defaultTabId: Long = 0,
    @JsonProperty("gift_sort_strategy")
    val giftSortStrategy: List<JsonNode> = emptyList(),
    @JsonProperty("event_track")
    val eventTrack: JsonNode? = null,
    @JsonProperty("fetch_strategy")
    val fetchStrategy: Int = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GiftsInfo(
    @JsonProperty("new_gift_id")
    val newGiftId: Long = 0,
    @JsonProperty("fansclub_gift_ids")
    val fansclubGiftIds: List<Long> = emptyList(),
    @JsonProperty("speedy_gift_id")
    val speedyGiftId: Long = 0,
    @JsonProperty("gift_words")
    val giftWords: String = "",
    @JsonProperty("gift_group_infos")
    val giftGroupInfos: List<GiftGroupInfo> = emptyList(),
    @JsonProperty("free_cell_items")
    val freeCellItems: List<JsonNode> = emptyList(),
    @JsonProperty("honor_gift_ids")
    val honorGiftIds: List<Long> = emptyList(),
    @JsonProperty("game_gift_items")
    val gameGiftItems: List<JsonNode> = emptyList(),
    @JsonProperty("noble_gift_ids")
    val nobleGiftIds: List<Long> = emptyList(),
    @JsonProperty("hide_recharge_entry")
    val hideRechargeEntry: Boolean = false,
    @JsonProperty("gift_entrance_icon")
    val giftEntranceIcon: GiftImage? = null,
    @JsonProperty("vip_gift_ids")
    val vipGiftIds: List<Long> = emptyList(),
    @JsonProperty("gift_combo_infos")
    val giftComboInfos: List<GiftComboInfo> = emptyList(),
    @JsonProperty("speedy_gift_popup_info")
    val speedyGiftPopupInfo: JsonNode? = null,
    @JsonProperty("first_recharge_speedy_gift_id")
    val firstRechargeSpeedyGiftId: Long = 0,
    @JsonProperty("msg_process_filter")
    val msgProcessFilter: JsonNode? = null,
    @JsonProperty("extra_params")
    val extraParams: JsonNode? = null,
    @JsonProperty("gift_search_config")
    val giftSearchConfig: JsonNode? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GiftGroupInfo(
    @JsonProperty("group_count")
    val groupCount: Long = 0,
    @JsonProperty("group_text")
    val groupText: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GiftComboInfo(
    @JsonProperty("combo_count")
    val comboCount: Long = 0,
    @JsonProperty("combo_effect_img")
    val comboEffectImg: GiftImage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GiftItem(
    val id: Long = 0,
    val name: String = "",
    val describe: String = "",
    val image: GiftImage? = null,
    val icon: GiftImage? = null,
    @JsonProperty("webp_image")
    val webpImage: GiftImage? = null,
    val notify: Boolean = false,
    val duration: Long = 0,
    @JsonProperty("for_linkmic")
    val forLinkmic: Boolean = false,
    val doodle: Boolean = false,
    @JsonProperty("for_fansclub")
    val forFansclub: Boolean = false,
    val combo: Boolean = false,
    val type: Int = 0,
    @JsonProperty("diamond_count")
    val diamondCount: Long = 0,
    @JsonProperty("is_displayed_on_panel")
    val displayedOnPanel: Boolean = false,
    @JsonProperty("primary_effect_id")
    val primaryEffectId: Long = 0,
    @JsonProperty("asset_ids")
    val assetIds: List<Long> = emptyList(),
    @JsonProperty("required_assets")
    val requiredAssets: List<Long> = emptyList(),
    @JsonProperty("gift_source")
    val giftSource: Int = 0,
    @JsonProperty("gift_scene")
    val giftScene: Int = 0,
    @JsonProperty("for_custom")
    val forCustom: Boolean = false,
    @JsonProperty("is_locked")
    val locked: Boolean = false,
    @JsonProperty("is_gray")
    val gray: Boolean = false,
    @JsonProperty("scheme_url")
    val schemeUrl: String = "",
    @JsonProperty("event_name")
    val eventName: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GiftImage(
    @JsonProperty("url_list")
    val urlList: List<String> = emptyList(),
    val uri: String = "",
    val height: Long = 0,
    val width: Long = 0,
    @JsonProperty("avg_color")
    val avgColor: String = "",
    @JsonProperty("image_type")
    val imageType: Int = 0,
    @JsonProperty("open_web_url")
    val openWebUrl: String = "",
    @JsonProperty("is_animated")
    val animated: Boolean = false,
    @JsonProperty("flex_setting_list")
    val flexSettingList: List<JsonNode> = emptyList(),
    @JsonProperty("text_setting_list")
    val textSettingList: List<JsonNode> = emptyList(),
)
