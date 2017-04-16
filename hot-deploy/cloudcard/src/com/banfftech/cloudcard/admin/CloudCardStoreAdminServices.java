package com.banfftech.cloudcard.admin;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilFormatOut;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.banfftech.cloudcard.CloudCardHelper;
import com.banfftech.cloudcard.constant.CloudCardConstant;
import com.banfftech.cloudcard.lbs.BaiduLBSUtil;

import javolution.util.FastMap;

/**
 * 后台店铺管理相关服务
 * 
 * @author ChenYu
 *
 */
public class CloudCardStoreAdminServices {

    public static final String module = CloudCardStoreAdminServices.class.getName();
    public static final String resourceError = "cloudcardErrorUiLabels";

    /**
     * 后台页面创建店铺的服务
     * 
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> createCloudCardStore(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dispatcher.getDelegator();
        Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String storeName = (String) context.get("storeName"); // 商家名
        String description = (String) context.get("description"); // 描述
        String storeOwnerName = (String) context.get("storeOwnerName"); // 店主名
        String storeOwnerTeleNumber = (String) context.get("storeOwnerTeleNumber"); // 店主电话
        String geoCountry = (String) context.get("geoCountry"); // 国家GeoId
        String geoProvince = (String) context.get("geoProvince"); // 省、直辖市GeoId
        String geoCity = (String) context.get("geoCity"); // 市GeoId
        String geoCounty = (String) context.get("geoCounty"); // 县GeoId
        String address1 = (String) context.get("address1"); // 详细地址1
        String address2 = (String) context.get("address2"); // 详细地址2
        String postalCode = (String) context.get("postalCode"); // 邮编
        if (UtilValidate.isEmpty(postalCode)) {
            postalCode = "99999"; // 邮编没传的情况先默认一个值
        }
        String longitude = (String) context.get("longitude"); // 经度
        String latitude = (String) context.get("latitude"); // 纬度
        String allowCrossStorePay = (String) context.get("allowCrossStorePay"); // 是否允许本店的卡去跨店消费
        allowCrossStorePay = allowCrossStorePay.toUpperCase();
        String level = (String) context.get("level"); // 信用等级
        BigDecimal creditLimit = (BigDecimal) context.get("creditLimit"); // 卖卡限额
        creditLimit = creditLimit.setScale(CloudCardHelper.decimals, CloudCardHelper.rounding);

        boolean newManager = UtilValidate.isNotEmpty(storeOwnerTeleNumber) && UtilValidate.isInternationalPhoneNumber(storeOwnerTeleNumber);
        String cloudCardStroreId;
        String storeOwnerPartyId = null;
        try {

            // 先检查传入的店长电话是否已经开过店
            if (newManager) {
                GenericValue userByTeleNumber = CloudCardHelper.getUserByTeleNumber(delegator, storeOwnerTeleNumber);
                if (UtilValidate.isNotEmpty(userByTeleNumber)) {
                    storeOwnerPartyId = userByTeleNumber.getString("partyId");
                    List<String> storeIds = CloudCardHelper.getOrganizationPartyId(delegator, storeOwnerPartyId);
                    if (UtilValidate.isNotEmpty(storeIds)) {
                        return ServiceUtil.returnError("输入的店主手机号已经开过店了！");
                    }
                }
            }

            // 创建 PartyGroup实体
            Map<String, Object> createPartyGroupOutMap = dispatcher.runSync("createPartyGroup", UtilMisc.toMap("userLogin", userLogin, "groupName", storeName,
                    "description", description, "preferredCurrencyUomId", CloudCardConstant.DEFAULT_CURRENCY_UOM_ID, "statusId", "PARTY_ENABLED"));
            if (!ServiceUtil.isSuccess(createPartyGroupOutMap)) {
                return createPartyGroupOutMap;
            }

            cloudCardStroreId = (String) createPartyGroupOutMap.get("partyId");

            // 创建partyRole
            Map<String, Object> createPartyRoleOutMap = dispatcher.runSync("createPartyRole",
                    UtilMisc.toMap("userLogin", userLogin, "partyId", cloudCardStroreId, "roleTypeId", "INTERNAL_ORGANIZATIO"));
            if (!ServiceUtil.isSuccess(createPartyRoleOutMap)) {
                return createPartyRoleOutMap;
            }
            createPartyRoleOutMap = dispatcher.runSync("createPartyRole",
                    UtilMisc.toMap("userLogin", userLogin, "partyId", cloudCardStroreId, "roleTypeId", "DISTRIBUTOR"));
            if (!ServiceUtil.isSuccess(createPartyRoleOutMap)) {
                return createPartyRoleOutMap;
            }

            // 创建店主
            if (newManager) {
                if (UtilValidate.isEmpty(storeOwnerPartyId)) {
                    String firstName = storeOwnerTeleNumber;
                    String lastName = "86";
                    if (UtilValidate.isNotEmpty(storeOwnerName)) {
                        // TODO 中文姓名是否要通过某种算法分拆出 firstName 和 LastName 呢？
                        firstName = storeOwnerName;
                    }
                    Map<String, Object> createPersonMap = UtilMisc.toMap("userLogin", userLogin, "firstName", firstName, "lastName", lastName);
                    createPersonMap.put("preferredCurrencyUomId", CloudCardConstant.DEFAULT_CURRENCY_UOM_ID);
                    Map<String, Object> personOutMap = dispatcher.runSync("createPerson", createPersonMap);
                    if (ServiceUtil.isError(personOutMap)) {
                        return personOutMap;
                    }
                    storeOwnerPartyId = (String) personOutMap.get("partyId");
                    // 自定义个前缀CCM 表示 Cloud Card Manager，减少冲突
                    String storeOwnerUserLoginId = "CCM" + delegator.getNextSeqId("UserLogin");
                    // 随机一个密码
                    String currentPassword = UtilFormatOut.padString(String.valueOf(Math.round((Math.random() * 10e6))), 6, false, '0');
                    Map<String, Object> userLoginOutMap = dispatcher.runSync("createUserLogin",
                            UtilMisc.toMap("userLogin", userLogin, "userLoginId", storeOwnerUserLoginId, "partyId", storeOwnerPartyId, "currentPassword",
                                    currentPassword, "currentPasswordVerify", currentPassword, "requirePasswordChange", CloudCardConstant.IS_Y));
                    if (ServiceUtil.isError(userLoginOutMap)) {
                        return userLoginOutMap;
                    }

                    // 允许手机号登录app
                    Map<String, Object> partyTelecomOutMap = dispatcher.runSync("createPartyTelecomNumber", UtilMisc.toMap("userLogin", userLogin,
                            "contactMechPurposeTypeId", "AS_USER_LOGIN_ID", "partyId", storeOwnerPartyId, "contactNumber", storeOwnerTeleNumber));
                    if (ServiceUtil.isError(partyTelecomOutMap)) {
                        return partyTelecomOutMap;
                    }
                }

                // 保证 店长 partyId 具有 MANAGER 角色
                Map<String, Object> ensurePartyRoleOut = dispatcher.runSync("ensurePartyRole",
                        UtilMisc.toMap("partyId", storeOwnerPartyId, "roleTypeId", "MANAGER"));
                if (ServiceUtil.isError(ensurePartyRoleOut)) {
                    return ensurePartyRoleOut;
                }
                // 关联店主与店
                Map<String, Object> relationOutMap = dispatcher.runSync("createPartyRelationship",
                        UtilMisc.toMap("userLogin", userLogin, "partyIdFrom", cloudCardStroreId, "partyIdTo", storeOwnerPartyId, "roleTypeIdFrom",
                                "INTERNAL_ORGANIZATIO", "roleTypeIdTo", "MANAGER", "partyRelationshipTypeId", "EMPLOYMENT"));
                if (ServiceUtil.isError(relationOutMap)) {
                    return relationOutMap;
                }

            }

            // 商家二维码
            CloudCardHelper.getOrGeneratePartyGroupQRcode(cloudCardStroreId, delegator);

            // 是否允许跨店的开关
            Map<String, Object> createPartyAttributeOutMap = dispatcher.runSync("createPartyAttribute",
                    UtilMisc.toMap("userLogin", userLogin, "partyId", cloudCardStroreId, "attrName", "allowCrossStorePay", "attrValue", allowCrossStorePay));
            if (!ServiceUtil.isSuccess(createPartyAttributeOutMap)) {
                return createPartyAttributeOutMap;
            }

            // 信用等级
            Map<String, Object> createPartyClassificationOutMap = dispatcher.runSync("createPartyClassification",
                    UtilMisc.toMap("userLogin", userLogin, "partyId", cloudCardStroreId, "partyClassificationGroupId", level));
            if (!ServiceUtil.isSuccess(createPartyClassificationOutMap)) {
                return createPartyClassificationOutMap;
            }

            // geo相关
            String geoDataSourceId = "GEOPT_BAIDU";
            Map<String, Object> createGeoPointOutMap = dispatcher.runSync("createGeoPoint", UtilMisc.toMap("userLogin", userLogin, "latitude", latitude,
                    "longitude", longitude, "information", address1, "dataSourceId", geoDataSourceId));
            if (!ServiceUtil.isSuccess(createGeoPointOutMap)) {
                return createGeoPointOutMap;
            }

            String geoPointId = (String) createGeoPointOutMap.get("geoPointId");
            GenericValue partyGeoPoint = delegator.makeValue("PartyGeoPoint",
                    UtilMisc.toMap("partyId", cloudCardStroreId, "geoPointId", geoPointId, "fromDate", UtilDateTime.nowTimestamp()));
            partyGeoPoint.create();

            // 店铺地址
            String contactMechPurposeTypeId = "GENERAL_LOCATION";
            Map<String, Object> createPartyPostalAddressOutMap = dispatcher.runSync("createPartyPostalAddress",
                    UtilMisc.toMap("userLogin", userLogin, "toName", storeName, "partyId", cloudCardStroreId, "countryGeoId", geoCountry, "stateProvinceGeoId",
                            geoProvince, "city", geoCity, "countyGeoId", geoCounty, "address1", address1, "address2", address2, "postalCode", postalCode,
                            "contactMechPurposeTypeId", contactMechPurposeTypeId, "geoPointId", geoPointId));
            if (!ServiceUtil.isSuccess(createPartyPostalAddressOutMap)) {
                return createPartyPostalAddressOutMap;
            }

            // 创建相关金融账号
            // 收款用
            Map<String, Object> createReceiptAccountOutMap = dispatcher.runSync("createFinAccount",
                    UtilMisc.toMap("userLogin", userLogin, "currencyUomId", CloudCardConstant.DEFAULT_CURRENCY_UOM_ID, "finAccountName", storeName + "店铺收款账户",
                            "finAccountTypeId", "DEPOSIT_ACCOUNT", "organizationPartyId", CloudCardConstant.PLATFORM_PARTY_ID, "ownerPartyId",
                            cloudCardStroreId, "statusId", "FNACT_ACTIVE", "postToGlAccountId", "111000", "isRefundable", CloudCardConstant.IS_Y));
            if (!ServiceUtil.isSuccess(createReceiptAccountOutMap)) {
                return createReceiptAccountOutMap;
            }

            // 结算用？
            Map<String, Object> createSettlementAccountOutMap = dispatcher.runSync("createFinAccount",
                    UtilMisc.toMap("userLogin", userLogin, "currencyUomId", CloudCardConstant.DEFAULT_CURRENCY_UOM_ID, "finAccountName", storeName + "店铺结算账户",
                            "finAccountTypeId", "BANK_ACCOUNT", "organizationPartyId", CloudCardConstant.PLATFORM_PARTY_ID, "ownerPartyId", cloudCardStroreId,
                            "statusId", "FNACT_ACTIVE", "postToGlAccountId", "210000", "isRefundable", CloudCardConstant.IS_Y));
            if (!ServiceUtil.isSuccess(createSettlementAccountOutMap)) {
                return createSettlementAccountOutMap;
            }

            // 卖卡限额用
            Map<String, Object> createCreditLimitAccountOutMap = dispatcher.runSync("createFinAccount",
                    UtilMisc.toMap("userLogin", userLogin, "currencyUomId", CloudCardConstant.DEFAULT_CURRENCY_UOM_ID, "finAccountName", storeName + "卖卡限额账户",
                            "finAccountTypeId", "SVCCRED_ACCOUNT", "organizationPartyId", CloudCardConstant.PLATFORM_PARTY_ID, "ownerPartyId",
                            cloudCardStroreId, "statusId", "FNACT_ACTIVE", "postToGlAccountId", "111000", "isRefundable", CloudCardConstant.IS_Y,
                            "replenishLevel", creditLimit));
            if (!ServiceUtil.isSuccess(createCreditLimitAccountOutMap)) {
                return createCreditLimitAccountOutMap;
            }

            if (creditLimit.compareTo(CloudCardHelper.ZERO) > 0) {
                // 添加卖卡限额
                String creditLimitAccountId = (String) createCreditLimitAccountOutMap.get("finAccountId");
                Map<String, Object> createFinAccountAuthOutMap = dispatcher.runSync("createFinAccountAuth",
                        UtilMisc.toMap("userLogin", userLogin, "locale", locale, "currencyUomId", CloudCardConstant.DEFAULT_CURRENCY_UOM_ID, "finAccountId",
                                creditLimitAccountId, "amount", creditLimit.negate(), "fromDate",
                                UtilDateTime.adjustTimestamp(UtilDateTime.nowTimestamp(), Calendar.SECOND, -2)));
                if (ServiceUtil.isError(createFinAccountAuthOutMap)) {
                    return createFinAccountAuthOutMap;
                }
            }

            // 上传poi数据
            Map<String, Object> params = FastMap.newInstance();
            params.put("ak", EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.ak", delegator));
            params.put("geotable_id", EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.getTableId", delegator));
            params.put("title", storeName);
            params.put("address", address1);
            // 纬度
            params.put("latitude", latitude);
            // 经度
            params.put("longitude", longitude);
            // params.put("tags", "酒店");
            params.put("coord_type", "3");
            // 自定义列
            params.put("storeId", UtilMisc.toInteger(cloudCardStroreId));
            BaiduLBSUtil bdLbs = new BaiduLBSUtil();
            String ret = bdLbs.createPOI(params);
            Debug.logInfo("== createPOI return: " + ret, module);

        } catch (GenericServiceException e1) {
            Debug.logError(e1.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        } catch (GenericEntityException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }

        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("storeId", cloudCardStroreId);
        return result;
    }
}
