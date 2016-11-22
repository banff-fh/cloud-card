package com.banfftech.cloudcard.test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.ServiceUtil;
import org.ofbiz.service.testtools.OFBizTestCase;

import com.banfftech.cloudcard.CloudCardHelper;

/**
 * @author subenkun
 *
 */
public class CloudCardServicesTest extends OFBizTestCase {

	public CloudCardServicesTest(String name) {
		super(name);
	}
	
	public void testCloudCardServicesOperations() throws GenericServiceException, GenericEntityException{
		GenericValue userLogin = delegator.findOne("UserLogin", UtilMisc.toMap("userLoginId", "subenkun"), false);
        Map<String, Object> ctx = new HashMap<String, Object>();
        //激活开卡业务
        List<String> organizationPartyIds = CloudCardHelper.getOrganizationPartyId(delegator, userLogin.getString("partyId"));
		BigDecimal amountBig = BigDecimal.ZERO; 

        ctx.put("organizationPartyId", organizationPartyIds.get(0));
        ctx.put("teleNumber", "13811112227");
        ctx.put("cardCode", "02148456032481356127");
        ctx.put("amount", amountBig.add(BigDecimal.valueOf(UtilMisc.toDouble("200.00"))));
        ctx.put("userLogin", userLogin);
        Debug.logError( organizationPartyIds.get(0), null);
        Map<String, Object> resp = dispatcher.runSync("activateCloudCardAndRecharge", ctx, 2000,true);
        assertTrue("Service 'activateCloudCardAndRecharge' result success", ServiceUtil.isSuccess(resp));

        BigDecimal amount = (BigDecimal) resp.get("amount");
        BigDecimal cardBalance = (BigDecimal) resp.get("cardBalance");
        assertEquals("200.0", String.valueOf(amount));
        assertEquals("200.0", String.valueOf(cardBalance));
        ctx.clear();
        //充值业务
        ctx.put("organizationPartyId", organizationPartyIds.get(0));
        ctx.put("cardId", "10000");
        ctx.put("amount", amountBig.add(BigDecimal.valueOf(UtilMisc.toDouble("200.00"))));
        ctx.put("userLogin", userLogin);
        resp = dispatcher.runSync("rechargeCloudCard", ctx, 2000,true);
        assertTrue("Service 'rechargeCloudCard' result success", ServiceUtil.isSuccess(resp));
      
        String customerPartyId = (String) resp.get("customerPartyId");
        amount = (BigDecimal) resp.get("amount");
        BigDecimal actualBalance = (BigDecimal) resp.get("actualBalance");
        assertEquals("1400.00", String.valueOf(actualBalance));
        assertEquals("10010", customerPartyId);
        ctx.clear();

        //支付服务
        ctx.put("organizationPartyId", organizationPartyIds.get(0));
        ctx.put("cardCode", "00812134736130161527");
        ctx.put("amount", amountBig.add(BigDecimal.valueOf(UtilMisc.toDouble("100.00"))));
        ctx.put("userLogin", userLogin);
        resp = dispatcher.runSync("cloudCardWithdraw", ctx, 2000,true);
        assertTrue("Service 'cloudCardWithdraw' result success", ServiceUtil.isSuccess(resp));

        amount = (BigDecimal) resp.get("amount");
        cardBalance = (BigDecimal) resp.get("cardBalance");
        customerPartyId = (String) resp.get("customerPartyId");
        assertEquals("100.0", String.valueOf(amount));
        assertEquals("1300.00", String.valueOf(cardBalance));
        assertEquals("10010", customerPartyId);
        ctx.clear();
        
        //卡授权
        Timestamp dateTime = UtilDateTime.nowTimestamp();
        ctx.put("teleNumber", "18711112222");
        ctx.put("cardId", "10000");
        ctx.put("amount", amountBig.add(BigDecimal.valueOf(UtilMisc.toDouble("100.00"))));
        ctx.put("fromDate", dateTime);
        ctx.put("thruDate", UtilDateTime.addDaysToTimestamp(dateTime, 1));
        ctx.put("userLogin", userLogin);
        resp = dispatcher.runSync("createCardAuth", ctx, 2000,true);
        assertTrue("Service 'cloudCardWithdraw' result success", ServiceUtil.isSuccess(resp));
        ctx.clear();
        
	}
}
