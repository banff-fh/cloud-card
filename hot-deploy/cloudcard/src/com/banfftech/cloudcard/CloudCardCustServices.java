package com.banfftech.cloudcard;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilFormatOut;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.auth0.jwt.JWTSigner;
import com.banfftech.cloudcard.constant.CloudCardConstant;
import com.banfftech.cloudcard.lbs.BaiduLBSUtil;
import com.banfftech.cloudcard.util.CloudCardLevelScoreUtil;

import javolution.util.FastList;
import javolution.util.FastMap;

public class CloudCardCustServices {
	public static final String module = CloudCardCustServices.class.getName();

	/**
	 * 附近的店铺
	 * 
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> userStoreListLBS(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		
        double longitude = Double.parseDouble(UtilFormatOut.checkNull((String) context.get("longitude"), "0.00"));
        double latitude = Double.parseDouble(UtilFormatOut.checkNull((String) context.get("latitude"), "0.00"));
        String storeName = (String) context.get("storeName");
        String region = (String) context.get("region");
        String geoId = null;
        String geoTypeId = null;
		List<GenericValue> countyList = FastList.newInstance();
		double exp = 10e-10;
        // 经度最大是180° 最小是-180° 纬度最大是90° 最小是-90°
        if (Math.abs(longitude) - 180.00 > exp || Math.abs(latitude) - 90.00 > exp) {
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardAbnormalPositioning", locale));
        }

		String ak = EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.ak", delegator);
		String getTableId = EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.getTableId", delegator);
		String radius = EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.radius", delegator);
		String CoordType = EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.CoordType", delegator);
		String q = EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.q", delegator);

		Map<String, Object> params = FastMap.newInstance();
		params.put("ak", ak);
		params.put("geotable_id", getTableId);
		params.put("q", q);
		params.put("Coord_type", CoordType);
		
		List<Map<String,Object>> storeList = FastList.newInstance();
		
		List<GenericValue> partyGroupList = FastList.newInstance();
		JSONObject lbsResult = null;
        if(UtilValidate.isNotEmpty(storeName)){
        	try {
				partyGroupList = delegator.findList("PartyGroup",
						EntityCondition.makeCondition("groupName", EntityOperator.LIKE, "%" + storeName + "%"), UtilMisc.toSet("partyId"), null, null, true);
			} catch (GenericEntityException e) {
				Debug.logError(e.getMessage(), module);
	            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
			}
        	
        	String startChar = "[";
        	String endTmpChar = "]";
        	int partyGroupListSize = partyGroupList.size();
        	for(int i = 0;i < partyGroupListSize;i++){
        		startChar += partyGroupList.get(i).get("partyId").toString();
        		if(i == (partyGroupListSize-1 )){
        			startChar += endTmpChar;
        		}else{
        			startChar+=",";
        		}
        	}
        	if(UtilValidate.isNotEmpty(region)){
            	params.put("region", region);
        	}
        	params.put("filter", "storeId:"+ startChar);
        	lbsResult = JSONObject.parseObject(BaiduLBSUtil.local(params));
        }else{
    		params.put("location", longitude + "," + latitude);
    		params.put("radius", radius);
        	lbsResult = JSONObject.parseObject(BaiduLBSUtil.nearby(params));
        	
        	String geocoder = BaiduLBSUtil.geocoder(UtilMisc.toMap("ak", ak, "location", latitude + "," + longitude, "output", "json", "callback", "showLocation"));
        	if(UtilValidate.isNotEmpty(geocoder)){
        		geocoder = geocoder.replace("showLocation&&showLocation(", "");
            	geocoder = geocoder.replace(")", "");
        		JSONObject addressJSONObject = JSONObject.parseObject(JSONObject.parseObject(JSONObject.parseObject(geocoder).getString("result")).getString("addressComponent"));
            	region = addressJSONObject.getString("city");
            	if(UtilValidate.isNotEmpty(region)){
            		try {
            			List<GenericValue> geoList = delegator.findList("Geo", EntityCondition.makeCondition("geoName",EntityOperator.LIKE, region.replace("市", "") + "%"), UtilMisc.toSet("geoId"), null, null, true);
            			String cityId = null;
            			if(UtilValidate.isNotEmpty(geoList)){
            				cityId = geoList.get(0).getString("geoId");
            				geoId = cityId;
            				geoTypeId = "CITY";
            				Map<String, Object> cityMap = dispatcher.runSync("getProvinceOrCityOrArea", UtilMisc.toMap("userLogin",userLogin,"geoAssocTypeId", "CITY_COUNTY","cityId",cityId));
            				if(UtilValidate.isNotEmpty(cityMap)){
            					countyList = UtilGenerics.checkList(cityMap.get("countyList"));
            				}
            				
            			}
					} catch (GenericServiceException e) {
						Debug.logError(e.getMessage(), module);
			            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
					} catch (GenericEntityException e) {
						Debug.logError(e.getMessage(), module);
			            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
					}
            	}
        	}
        	
        }
        
		
		
		//如果地图返回状态非0，全部定义为手机定位异常
		if(!"0".equals(lbsResult.get("status").toString())){
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardAbnormalPositioning", locale));
		}
		if(!"0".equalsIgnoreCase(lbsResult.get("total").toString())){
			JSONArray jsonArray = JSONObject.parseArray(lbsResult.get("contents").toString());
			for(int i = 0 ;i<jsonArray.size();i++){
				
				try {
				
					boolean isGroupOwner = CloudCardHelper.isStoreGroupOwner(delegator,jsonArray.getJSONObject(i).getObject("storeId", String.class), true);
					
					context.put("storeId", jsonArray.getJSONObject(i).getObject("storeId",String.class));
					//获取店铺信息
					Map<String,Object> stroeInfo = userGetStoreInfo(dctx, context);
						
					Map<String, Object> storeMap = FastMap.newInstance();
					storeMap.put("storeName",stroeInfo.get("storeName"));
					storeMap.put("address",stroeInfo.get("storeAddress"));
					storeMap.put("telNum",stroeInfo.get("storeTeleNumber"));
					storeMap.put("storeId",stroeInfo.get("storeId"));
					storeMap.put("isGroupOwner",CloudCardHelper.bool2YN(isGroupOwner));
					storeMap.put("isHasCard",stroeInfo.get("isHasCard"));
					storeMap.put("distance",jsonArray.getJSONObject(i).getObject("distance",String.class) );
					if (UtilValidate.isNotEmpty(stroeInfo.get("longitude")) && UtilValidate.isNotEmpty(stroeInfo.get("latitude"))) {
						storeMap.put("location", "["+stroeInfo.get("longitude")+","+stroeInfo.get("latitude")+"]");
					} else {
						storeMap.put("location", jsonArray.getJSONObject(i).getObject("location", String.class));
					}
					storeList.add(storeMap);
				} catch (GenericEntityException e) {
					Debug.logError(e.getMessage(), module);
		            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
				}
			}
		}
		
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("listSize", lbsResult.get("total").toString());
		result.put("longitude", String.valueOf(longitude));
		result.put("latitude",String.valueOf(latitude));
		result.put("region",region);
		result.put("geoId",geoId);
		result.put("geoTypeId",geoTypeId);
		result.put("countyList",countyList);
		result.put("storeList", storeList);
		return result;
	}
	
	/**
	 * 查看店铺简要信息
	 * 
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> userGetStoreInfo(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		
		String storeId = (String) context.get("storeId");
		String storeName = null;
		String storeImg = null;
		String storeAddress = null;
		String storeTeleNumber = null;
		String longitude = null;
		String latitude = null;
		String isHasCard = CloudCardConstant.IS_N;
		
		//获取店铺信息
		Map<String,Object> cardAndStoreInfoMap = CloudCardHelper.getCardAndStoreInfo(dctx, context);
		if(UtilValidate.isNotEmpty(cardAndStoreInfoMap)){
			storeName = (String) cardAndStoreInfoMap.get("storeName");
			List<Object> cloudCardList  = UtilGenerics.checkList(cardAndStoreInfoMap.get("cloudCardList")) ;
			if(cloudCardList.size() <= 0 ){
				isHasCard = CloudCardConstant.IS_N;
			}else{
				isHasCard = CloudCardConstant.IS_Y;
			}
		}
		
		storeImg = EntityUtilProperties.getPropertyValue("cloudcard","cardImg." + storeId,delegator);


		Map<String, Object> geoAndContactMechInfoMap = CloudCardHelper.getGeoAndContactMechInfoByStoreId(delegator,locale,storeId);
        if(UtilValidate.isNotEmpty(geoAndContactMechInfoMap)){
        	storeAddress = (String) geoAndContactMechInfoMap.get("storeAddress");
        	storeTeleNumber = (String) geoAndContactMechInfoMap.get("storeTeleNumber");
        	longitude = (String) geoAndContactMechInfoMap.get("longitude");
        	latitude = (String) geoAndContactMechInfoMap.get("latitude");
        }
		
        //获取店家商铺详细信息
        List<GenericValue> storeInfoImgList = FastList.newInstance();
        try {
        	storeInfoImgList = delegator.findByAnd("PartyContentAndDataResourceDetail", UtilMisc.toMap("partyId", storeId,"partyContentTypeId", "STORE_IMG","contentTypeId","ACTIVITY_PICTURE","statusId","CTNT_IN_PROGRESS"));
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
        
        //获取oss访问地址
        String ossUrl = EntityUtilProperties.getPropertyValue("cloudcard","oss.url","http://kupang.oss-cn-shanghai.aliyuncs.com/",delegator);

        //获取店铺收款二维码
        String storeCode = "";
        try {
        	GenericValue partyIdentification = delegator.findOne("PartyIdentification", UtilMisc.toMap("partyId", storeId, "partyIdentificationTypeId", "STORE_QR_CODE"),true);
        	if(UtilValidate.isNotEmpty(partyIdentification)){
        		storeCode = partyIdentification.getString("idValue");
        	}
        } catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("storeId", storeId);
		result.put("storeName", storeName);
		result.put("storeImg", storeImg);
		result.put("storeAddress", storeAddress);
		result.put("storeTeleNumber", storeTeleNumber);
		result.put("longitude", longitude);
		result.put("latitude", latitude);
		result.put("isHasCard", isHasCard);
		result.put("storeInfoImgList", storeInfoImgList);
		result.put("storeCode", storeCode);
		result.put("ossUrl", ossUrl);

		return result;
	}
	
	/**
	 * 查看查看圈子信息
	 * 
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> userGetGroupInfo(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		
		String storeId = (String) context.get("storeId");
		//获取圈子Id
		String partyGroupId = null;
		try {
			partyGroupId = CloudCardHelper.getGroupIdByStoreId(delegator,storeId,false);
		} catch (GenericEntityException e) {
		    Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		
		//获取圈名
		String groupName = null;
		GenericValue pg = null;
		try {
			pg = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", partyGroupId));
		} catch (GenericEntityException e) {
		    Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		
		if(UtilValidate.isNotEmpty(pg)){
			groupName = pg.getString("groupName");
		}
		
		
		List<String> storeIdList = FastList.newInstance();
		try {
			storeIdList = CloudCardHelper.getStoreGroupPartnerListByStoreId(delegator,storeId,true);
		} catch (GenericEntityException e) {
		    Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		
		List<Map<String,Object>> storeList = FastList.newInstance();
		if(UtilValidate.isNotEmpty(storeIdList)){
			for(String storeRs: storeIdList){
				String storeName = null;
				String storeIdTmp = null;
				String storeImg = null;
				String storeAddress = null;
				String storeTeleNumber = null;
				String longitude = null;
				String latitude = null;
				
				Boolean isGroupOwner = null;
				try {
				    isGroupOwner = CloudCardHelper.isStoreGroupOwner(delegator,storeRs,false);
				} catch (GenericEntityException e) {
				    Debug.logError(e.getMessage(), module);
		            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
				}
				
				GenericValue partyGroup = null;
				try {
					partyGroup = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", storeRs));
				} catch (GenericEntityException e) {
				    Debug.logError(e.getMessage(), module);
		            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
				}

				if (UtilValidate.isNotEmpty( partyGroup)) {
					storeName = partyGroup.getString("groupName");
					storeIdTmp = partyGroup.getString("partyId");
				}
				
				storeImg = EntityUtilProperties.getPropertyValue("cloudcard","cardImg." + storeRs,delegator);


				List<GenericValue> PartyAndContactMechs = FastList.newInstance();
				try {
					PartyAndContactMechs = delegator.findList("PartyAndContactMech", EntityCondition.makeCondition("partyId", storeRs), null, null, null, true);
				} catch (GenericEntityException e) {
				    Debug.logError(e.getMessage(), module);
		            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
				}

				if (UtilValidate.isNotEmpty(PartyAndContactMechs)) {
					for (GenericValue partyAndContactMech : PartyAndContactMechs) {
						if(partyAndContactMech.get("contactMechTypeId").toString().equals("POSTAL_ADDRESS")){
							storeAddress = (String) partyAndContactMech.get("paAddress1");
						}else if(partyAndContactMech.get("contactMechTypeId").toString().equals("TELECOM_NUMBER")){
							storeTeleNumber = (String) partyAndContactMech.get("tnContactNumber");
						}
					}
				}
				
				
				List<GenericValue> partyAndGeoPoints = FastList.newInstance();
				try {
					partyAndGeoPoints = delegator.findList("PartyAndGeoPoint", EntityCondition.makeCondition("partyId", storeRs), null, null, null, true);
				} catch (GenericEntityException e) {
				    Debug.logError(e.getMessage(), module);
		            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
				}
				
				if(UtilValidate.isNotEmpty(partyAndGeoPoints)){
					longitude = partyAndGeoPoints.get(0).getString("longitude");
					latitude = partyAndGeoPoints.get(0).getString("latitude");
				}
				
				Map<String,Object> storeMap = FastMap.newInstance();
				storeMap.put("storeId", storeIdTmp);
				storeMap.put("storeName", storeName);
				storeMap.put("storeImg", storeImg);
				storeMap.put("storeAddress", storeAddress);
				storeMap.put("storeTeleNumber", storeTeleNumber);
				storeMap.put("isGroupOwner", CloudCardHelper.bool2YN(isGroupOwner));
				storeMap.put("longitude", longitude);
				storeMap.put("latitude", latitude);
				storeList.add(storeMap);
			}
		}
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("storeId", storeId);
		result.put("groupName", groupName);
		result.put("storeList", storeList);
		return result;
		
	}
	
    /**
     * C端扫码获取商户信息和卡列表
     * 
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> userScanCodeGetCardAndStoreInfo(DispatchContext dctx, Map<String, Object> context) {
        //根据二维码获取卡和店铺信息
        Map<String,Object> cardAndStoreInfoMap = CloudCardHelper.getCardAndStoreInfo(dctx, context);
        return cardAndStoreInfoMap;
    }
	
	/**
	 * C端选卡获取商户信息
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> getStoreInfoByCardId(DispatchContext dctx, Map<String, Object> context) {
//		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dctx.getDelegator();
//		GenericValue userLogin = (GenericValue) context.get("userLogin");
		Locale locale = (Locale) context.get("locale");
		
		String cardId = (String) context.get("cardId");
		String qrCode = (String) context.get("qrCode");

		String storeId = null;
		String groupName = null;
		
		GenericValue partyGroup = null;
		try {
			partyGroup = CloudCardHelper.getPartyGroupByQRcode(qrCode, delegator);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		
		if(UtilValidate.isNotEmpty(partyGroup)){
			storeId = partyGroup.getString("partyId");
			groupName = partyGroup.getString("groupName");
		}
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("cardId", cardId);
		result.put("qrCode", qrCode);
		result.put("storeId", storeId);
		result.put("storeName", groupName);
		return result;
	}
	
	/**
	 * C端购卡
	 * 
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> purchaseCard(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		String paymentType = (String) context.get("paymentType");
		String storeId = (String) context.get("storeId");
		String totalFee = (String) context.get("totalFee");
		String paymentService = (String) context.get("paymentService");

		
		// 传入的organizationPartyId必须是一个存在的partyGroup
		GenericValue partyGroup;
		try {
			partyGroup = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", storeId));
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		if(null == partyGroup ){
			Debug.logWarning("商户："+storeId + "不存在", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, 
					"CloudCardOrganizationPartyNotFound", UtilMisc.toMap("organizationPartyId", storeId), locale));
		}
		
		//生成的卡号
		String newCardCode = null;
		String description = partyGroup.getString("groupName")+"库胖卡";
		String customerPartyId = userLogin.getString("partyId");
		String cardId = "";
		Timestamp fromDate = UtilDateTime.adjustTimestamp(UtilDateTime.nowTimestamp(), Calendar.SECOND, -2);
		
		try {
			newCardCode = CloudCardHelper.generateCloudCardCode(delegator);
			String finAccountId = delegator.getNextSeqId("FinAccount");
			Map<String, Object> finAccountMap = FastMap.newInstance();
			finAccountMap.put("finAccountId", finAccountId);
			finAccountMap.put("finAccountTypeId", "GIFTCERT_ACCOUNT");
			finAccountMap.put("finAccountName", description);
			finAccountMap.put("finAccountCode", newCardCode);
			finAccountMap.put("organizationPartyId", CloudCardConstant.PLATFORM_PARTY_ID);
			finAccountMap.put("ownerPartyId", customerPartyId);
			finAccountMap.put("currencyUomId", "CNY");
			finAccountMap.put("postToGlAccountId", "213200");
			finAccountMap.put("isRefundable", "Y");
			finAccountMap.put("statusId", "FNACT_ACTIVE");
	        finAccountMap.put("fromDate", fromDate);
			
			//保存finaccount数据
			GenericValue finAccount = delegator.makeValue("FinAccount", finAccountMap);
			finAccount.create();
			
			//保存finaccountRole数据
			GenericValue finAccountRole = delegator.makeValue("FinAccountRole", UtilMisc.toMap( "finAccountId", finAccountId, "partyId", storeId, "roleTypeId", "DISTRIBUTOR","fromDate", fromDate));
			finAccountRole.create();
			
			
            // 创建PaymentMethod GiftCard
            Map<String, Object> giftCardInMap = FastMap.newInstance();
            giftCardInMap.putAll(context);
            giftCardInMap.put("cardNumber", newCardCode);
            giftCardInMap.put("description", description);
            giftCardInMap.put("customerPartyId", customerPartyId);
            giftCardInMap.put("finAccountId", finAccountId);
            giftCardInMap.put("fromDate", fromDate);
            Map<String, Object> giftCardOutMap = CloudCardHelper.createPaymentMethodAndGiftCard(dctx, giftCardInMap);
            if (ServiceUtil.isError(giftCardOutMap)) {
                return giftCardOutMap;
            }

            cardId = (String) giftCardOutMap.get("paymentMethodId");
			
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardGenCardNumberError", locale));
		}
		
		//预下单
		Map<String,Object> uniformOrderMap = null;
		try {
			Map<String,Object> initMap = FastMap.newInstance();
			initMap.put("userLogin", userLogin);
			initMap.put("cardId", cardId);
			initMap.put("paymentType", paymentType);
			initMap.put("body", "买卡");
			initMap.put("totalFee", totalFee);
			initMap.put("paymentService", paymentService);

			if(CloudCardConstant.PAY_CHANNEL_ALIPAY.equals(paymentType)){
				initMap.put("subject", "购买库胖卡");
			}else if(CloudCardConstant.PAY_CHANNEL_WXPAY.equals(paymentType)){
				initMap.put("tradeType", "APP");
			}
			uniformOrderMap = dispatcher.runSync("uniformOrder", initMap);
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("storeId", storeId);
		if(CloudCardConstant.PAY_CHANNEL_ALIPAY.equals(paymentType)){
			result.put("payInfo", uniformOrderMap.get("payInfo"));
		}else if(CloudCardConstant.PAY_CHANNEL_WXPAY.equals(paymentType)){
			result.put("appid", uniformOrderMap.get("appid"));
			result.put("noncestr", uniformOrderMap.get("noncestr"));
			result.put("partnerid", uniformOrderMap.get("partnerid"));
			result.put("package", "Sign=WXPay");
			result.put("prepayid", uniformOrderMap.get("prepayid"));
			result.put("timestamp", uniformOrderMap.get("timestamp"));
			result.put("sign",uniformOrderMap.get("sign"));
		}
		return result;
	}
	
	/**
	 * 获取付款二维码
	 * 
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> getPaymentQRCode(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		String qrCode = null;
		
		long expirationTime = Long.valueOf(EntityUtilProperties.getPropertyValue("cloudcard","qrCode.expirationTime","172800L",delegator));
		String iss = EntityUtilProperties.getPropertyValue("cloudcard","qrCode.issuer",delegator);
		String tokenSecret = EntityUtilProperties.getPropertyValue("cloudcard","qrCode.secret",delegator);
		//开始时间
		final long iat = System.currentTimeMillis() / 1000L; // issued at claim 
		//Token到期时间
		final long exp = iat + expirationTime; 
		//生成Token
		final JWTSigner signer = new JWTSigner(tokenSecret);
		final HashMap<String, Object> claims = new HashMap<String, Object>();
		claims.put("iss", iss);
		claims.put("user", userLogin.get("partyId"));
		claims.put("delegatorName", delegator.getDelegatorName());
		claims.put("exp", exp);
		claims.put("iat", iat);
		qrCode = signer.sign(claims);
		
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("refreshTime", expirationTime);
		result.put("qrCode", CloudCardConstant.CODE_PREFIX_PAY_ + qrCode);
		return result;
	}
	
	/**
     * C端根据店铺Id获取商户信息和卡列表
     * 
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> getCardAndStoreInfoByStoreId(DispatchContext dctx, Map<String, Object> context) {
        //根据二维码获取卡和店铺信息
        Map<String,Object> cardAndStoreInfoMap = CloudCardHelper.getCardAndStoreInfo(dctx, context);
        return cardAndStoreInfoMap;
    }
    
    
    /**
     * C端根据店铺名获取商户信息和卡列表
     * 
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> getCardAndStoreInfoByStoreName(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dispatcher.getDelegator();
        Locale locale = (Locale) context.get("locale");

    	String storeName = (String) context.get("storeName");
    	List<GenericValue> partyGroupList = FastList.newInstance();
        if (UtilValidate.isNotEmpty(storeName)) {
        	try {
				partyGroupList = delegator.findList("PartyGroup",
						EntityCondition.makeCondition("groupName", EntityOperator.LIKE, "%" + storeName + "%"), null, null, null, true);
			} catch (GenericEntityException e) {
				Debug.logError(e.getMessage(), module);
	            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
			}
        }
        
		List<Map<String, Object>> cardAndStoreInfoList = FastList.newInstance();
        for(GenericValue partyGroup:partyGroupList){
        	//根据二维码获取卡和店铺信息
        	context.put("storeId", partyGroup.getString("partyId"));
            Map<String,Object> cardAndStoreInfoMap = CloudCardHelper.getCardAndStoreInfo(dctx, context);
            cardAndStoreInfoList.add(cardAndStoreInfoMap);
        }
        
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("storeInfoList", cardAndStoreInfoList);
        return result;

    }
    
    /**
     * C端获取用户积分和等级
     * 
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> getUserLevelAndScore(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dispatcher.getDelegator();
        Locale locale = (Locale) context.get("locale");

        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String userPartyId = userLogin.getString("partyId");
        Map<String, Object> retMap = ServiceUtil.returnSuccess();

        // 根据二维码获取卡和店铺信息
        try {
            BigDecimal scoreAmount = CloudCardLevelScoreUtil.getUserScoreAmount(delegator, userPartyId);
            GenericValue userLevel = CloudCardLevelScoreUtil.getUserLevel(delegator, userPartyId);

            retMap.put("score", scoreAmount);
            retMap.put("userLevel", userLevel.getString("description"));
        } catch (GenericEntityException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }
        
        return retMap;
    }
    
    /**
     * C端获取个人信息
     * 
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> getUesrInfo(DispatchContext dctx, Map<String, Object> context){
    	LocalDispatcher dispatcher = dctx.getDispatcher();
    	Delegator delegator = dispatcher.getDelegator();
        Locale locale = (Locale) context.get("locale");
        
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String partyId = userLogin.getString("partyId");
        String userName = "";
        String teleNumber = "";
        try {
        	GenericValue person = delegator.findByPrimaryKeyCache("Person", UtilMisc.toMap("partyId", partyId));
        	if(UtilValidate.isNotEmpty(person)){
        		userName = (String) person.get("lastName");
        	}
        	
        	List<GenericValue> partyAndTelecomNumbers = delegator.findByAnd("PartyAndTelecomNumber", UtilMisc.toMap("partyId",partyId,"statusId","PARTY_ENABLED","statusId", "LEAD_ASSIGNED"));
        	if(UtilValidate.isNotEmpty(partyAndTelecomNumbers)){
        		GenericValue partyAndTelecomNumber = partyAndTelecomNumbers.get(0);
        		teleNumber = partyAndTelecomNumber.getString("contactNumber");
        	}
        	
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("userName", userName);
        result.put("teleNumber", teleNumber);
        return result;
    }
    
    /**
     * C端修改个人信息
     * 
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> updateUesrInfo(DispatchContext dctx, Map<String, Object> context){
    	LocalDispatcher dispatcher = dctx.getDispatcher();
    	Delegator delegator = dispatcher.getDelegator();
        Locale locale = (Locale) context.get("locale");
        
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String partyId = userLogin.getString("partyId");
        String userName = (String) context.get("userName");
        
        Map<String, Object> result = ServiceUtil.returnSuccess();
        GenericValue person;
		try {
			person = delegator.findByPrimaryKey("Person", UtilMisc.toMap("partyId", partyId));
			person.set("lastName", userName);
	        person.store();
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
        
        return result;
    }
    
    /**
     * C端修改个人信息
     * 
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> findCloudCardStore(DispatchContext dctx, Map<String, Object> context){
    	LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");

        double longitude = Double.parseDouble(UtilFormatOut.checkNull((String) context.get("longitude"), "0.00"));
        double latitude = Double.parseDouble(UtilFormatOut.checkNull((String) context.get("latitude"), "0.00"));
        //店铺名称
        String storeName = (String) context.get("storeName");
        //省市区
        String region = (String) context.get("region");

		double exp = 10e-10;
        // 经度最大是180° 最小是-180° 纬度最大是90° 最小是-90°
        if (Math.abs(longitude) - 180.00 > exp || Math.abs(latitude) - 90.00 > exp) {
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardAbnormalPositioning", locale));
        }

		String ak = EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.ak", delegator);
		String getTableId = EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.getTableId", delegator);
		String radius = EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.radius", delegator);
		String CoordType = EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.CoordType", delegator);
		String q = EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.q", delegator);

		Map<String, Object> params = FastMap.newInstance();
		params.put("ak", ak);
		params.put("geotable_id", getTableId);
		params.put("q", q);
		params.put("Coord_type", CoordType);
		
		params.put("location", longitude + "," + latitude);
		params.put("radius", radius);

		JSONObject lbsResult = null;
    	lbsResult = JSONObject.parseObject(BaiduLBSUtil.nearby(params));
        
        Map<String, Object> result = ServiceUtil.returnSuccess();
        GenericValue person;
        return result;
    }
    
    /**
     * 获取城市列表（模糊查询）
     * 
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> getCityOrAreaByCityName(DispatchContext dctx, Map<String, Object> context){
    	LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		String cityName = (String) context.get("cityName");
		
		List<GenericValue> cityList = FastList.newInstance();
		
		//geo查询条件
		EntityCondition geoNameCond = EntityCondition.makeCondition("geoName", EntityOperator.LIKE, "%" + cityName + "%");
		EntityCondition geoTypeIdCond = EntityCondition.makeCondition("geoTypeId", EntityOperator.EQUALS, "CITY");
		EntityCondition lookupConditions = EntityCondition.makeCondition(EntityOperator.AND, geoNameCond, geoTypeIdCond);
		
		try {
			cityList = delegator.findList("Geo", lookupConditions, UtilMisc.toSet("geoId", "geoTypeId", "geoName"), null, null, true );
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
    	Map<String, Object> result = ServiceUtil.returnSuccess();
    	result.put("cityList", cityList);
    	return result;
    }
    
    /**
     * 获取城市列表（模糊查询）
     * 
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> getCityOrAreaByGeoId(DispatchContext dctx, Map<String, Object> context){
    	LocalDispatcher dispatcher = dctx.getDispatcher();
		Locale locale = (Locale) context.get("locale");
    	String geoId = (String) context.get("geoId");
    	String geoTypeId = (String) context.get("geoTypeId");
		List<GenericValue> cities = FastList.newInstance();
		try {
			Map<String, Object> cityMap = dispatcher.runSync("getProvinceOrCityOrArea", UtilMisc.toMap("geoAssocTypeId", "CITY_COUNTY","cityId",geoId));
			if(UtilValidate.isNotEmpty(cityMap)){
				cities = UtilGenerics.checkList(cityMap.get("countyList"));
			}
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		
		//城市列表
		List<Map<String, Object>> cityList = FastList.newInstance();
		Map<String, Object> cityMap = FastMap.newInstance();
		if("CITY".equals(geoTypeId)){
			//增加一个固定的county(全城)
			cityMap.put("countyGeoId", geoId);
			cityMap.put("countyName", "全城");
			cityMap.put("geoType", "CITY");
			cityList.add(cityMap);
		}
		
		for(Map city : cities){
			cityMap = FastMap.newInstance();
			cityMap.put("countyGeoId", city.get("countyGeoId"));
			cityMap.put("countyName", city.get("countyName"));
			cityMap.put("geoType", city.get("geoType"));
			cityList.add(cityMap);
		}
		
    	Map<String, Object> result = ServiceUtil.returnSuccess();
    	result.put("cityList", cityList);
    	return result;
    }
    
    /**
     * 根据geoId查询店家列表
     * 
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> getCloudcardStoreByGeoId(DispatchContext dctx, Map<String, Object> context){
    	LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		String geoTypeId = (String) context.get("geoTypeId");
    	String geoId = (String) context.get("geoId");
		
    	EntityCondition lookupConditions = null;
    	//如果是市
    	if("CITY".equals(geoTypeId)){
    		lookupConditions = EntityCondition.makeCondition("city", EntityOperator.EQUALS, geoId);
        //如果是区
    	}else if("COUNTY".equals(geoTypeId)){
    		lookupConditions = EntityCondition.makeCondition("countyGeoId", EntityOperator.EQUALS, geoId);
    	}
    	
    	List<GenericValue> cloudcardGeoList = FastList.newInstance();
    	try {
    		cloudcardGeoList = delegator.findList("CloudcardPartyAndPostalAddress", lookupConditions, UtilMisc.toSet("partyId"), null, null, true);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
    	//店铺集合
		List<Map<String,Object>> storeList = FastList.newInstance();
    	//获取店铺信息
    	for(GenericValue cloudcardGeo : cloudcardGeoList){
    		context.put("storeId", cloudcardGeo.getString("partyId"));
			Map<String,Object> stroeInfo = userGetStoreInfo(dctx, context);
			Map<String, Object> storeMap = FastMap.newInstance();
			storeMap.put("storeName",stroeInfo.get("storeName"));
			storeMap.put("address",stroeInfo.get("storeAddress"));
			storeMap.put("telNum",stroeInfo.get("storeTeleNumber"));
			storeMap.put("storeId",stroeInfo.get("storeId"));
			//storeMap.put("isGroupOwner",CloudCardHelper.bool2YN(isGroupOwner));
			storeMap.put("isHasCard",stroeInfo.get("isHasCard"));
			if (UtilValidate.isNotEmpty(stroeInfo.get("longitude")) && UtilValidate.isNotEmpty(stroeInfo.get("latitude"))) {
				storeMap.put("location", "["+stroeInfo.get("longitude")+","+stroeInfo.get("latitude")+"]");
			} 
			storeList.add(storeMap);
    	}
		
    	Map<String, Object> result = ServiceUtil.returnSuccess();
    	result.put("storeList", storeList);
    	return result;
    }
    
    
}
