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

    /**
     * 发送登录短信验证码{@value}
     */
    public static final String LOGIN_SMS_TYPE = "Login";

    /**
     * 发送用户无卡买卡短信验证码{@value}
     */
    public static final String USER_PURCHASE_CARD_CAPTCHA_SMS_TYPE = "userPurchaseCardCaptcha";

    /**
     * 发送用户无卡充值短信验证码{@value}
     */
    public static final String USER_RECHARGE_CAPTCHA_SMS_TYPE = "userRecharge";

    /**
     * 发送用户消费短信验证码{@value}
     */
    public static final String USER_PAY_CAPTCHA_SMS_TYPE = "userPayCaptcha";

    /**
     * 发送用户消费通知短信{@value}
     */
    public static final String USER_PAY_SMS_TYPE = "userPay";

    /**
     * 发送用户充值通知短信{@value}
     */
    public static final String USER_RECHARGE_SMS_TYPE = "userRecharge";

    /**
     * 发送用户开卡成功通知短信{@value}
     */
    public static final String USER_PURCHASE_CARD_SMS_TYPE = "userPurchaseCard";

    /**
     * 发送用户成功授权通知短信{@value}
     */
    public static final String USER_CREATE_CARD_AUTH_TYPE = "userCreateCardAuth";

    /**
     * 发送用户成功解除授权通知短信{@value}
     */
    public static final String USER_REVOKE_CARD_AUTH_TYPE = "userRevokeCardAuth";

    /**
     * 发送用户转卡通知短信{@value}
     */
    public static final String USER_MODIFY_CARD_OWNER_TYPE = "userModifyCardOwner";
}
