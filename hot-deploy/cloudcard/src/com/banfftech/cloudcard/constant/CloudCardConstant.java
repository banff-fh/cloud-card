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
    public static final String STORE_GROUP_OWNER_ROLE_TYPE_ID = "CC_SG_OWNER";

    /**
     * 圈子普通成员的角色类型 {@value}
     */
    public static final String STORE_GROUP_PARTNER_ROLE_TYPE_ID = "CC_SG_PARTNER";

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

    /**
     * 圈友关系状态： 活跃状态 {@value}
     */
    public static final String SG_REL_STATUS_ACTIVE = "PREL_ACTIVE";

    /**
     * 圈友关系状态： 冻结状态 {@value}
     */
    public static final String SG_REL_STATUS_FROZEN = "PREL_FROZEN";

    /**
     * 支付方式类型（paymentMethodType）： 现金 {@value}
     */
    public static final String PMT_CASH = "CASH";

    /**
     * 支付方式类型（paymentMethodType）： 支付宝 {@value}
     */
    public static final String PMT_ALIPAY = "EXT_ALIPAY";

    /**
     * 支付方式类型（paymentMethodType）： 微信支付 {@value}
     */
    public static final String PMT_WXPAY = "EXT_WXPAY";

    /**
     * 付款渠道： 微信 {@value}
     */
    public static final String PAY_CHANNEL_WXPAY = "wxPay";

    /**
     * 付款渠道： 支付宝 {@value}
     */
    public static final String PAY_CHANNEL_ALIPAY = "aliPay";
    
    /**
     * 付款码前缀 {@value}
     */
    public static final String CODE_PREFIX_PAY_ = "user_pay_";
    
    /**
     * 积分账户的账户类型 {@value}
     */
    public static final String FANT_SCORE = "CC_SCORE_ACCOUNT";

}
