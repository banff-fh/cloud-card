package com.banfftech.cloudcard.constant;

public class CloudCardConstant {

    /**
     * 默认币种 {@value}
     */
    public static final String DEFAULT_CURRENCY_UOM_ID = "CNY";

    /**
     * 错误信息文件 {@value}
     */
    public static final String resourceError = "cloudcardErrorUiLabels";

    /**
     * account模块的错误信息文件 {@value}
     */
    public static final String resourceAccountingError = "AccountingErrorUiLabels";

    /**
     * 平台partyId {@value}
     */
    public static final String PLATFORM_PARTY_ID = "Company";

    /**
     * 授权给别人的云卡卡号前缀 {@value}
     */
    public static final String AUTH_CARD_CODE_PREFIX = "auth:";

    /**
     * 商家二维码前缀 {@value}
     */
    public static final String STORE_QR_CODE_PREFIX = "ccs-";

    /**
     * 圈子角色类型 {@value}
     * <p>
     * 圈子实际是一个partyGroup，但具有“{@value}”角色
     * </p>
     */
    public static final String STORE_GROUP_ROLE_TYPE_ID = "CC_STORE_GROUP";

    /**
     * 圈主的角色类型 {@value}
     */
    public static final String STORE_GROUP_OWNER_ROLE_TYPE_ID = "OWNER";

    /**
     * 圈子普通成员的角色类型 {@value}
     */
    public static final String STORE_GROUP_PARTNER_ROLE_TYPE_ID = "PARTNER";

    /**
     * 圈子成员与圈子的关系类型 {@value}
     */
    public static final String STORE_GROUP_PARTY_RELATION_SHIP_TYPE_ID = "GROUP_ROLLUP";

    /**
     * 表示是/否的 是 {@value}
     */
    public static final String IS_Y = "Y";

    /**
     * 表示是/否的 否 {@value}
     */
    public static final String IS_N = "N";

}
