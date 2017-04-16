<#--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->
<script type="text/javascript">
<#assign requestName><@ofbizUrl>getAssociatedGeoList</@ofbizUrl></#assign>
jQuery(document).ready( function() {

	/* copy from images/getDependentDropdownValues.js but support requestData*/
	function getDependentDropdownValuesColoudCard(request, requestData, targetField, responseName, keyName, descName, selected, callback, hide, hideTitle, inputField){
	    var target = '#' + targetField,
	    	input = '#' + inputField,
	    	targetTitle = target + '_title',
	    	optionList = '';

	    var $target = jQuery(target),
	    	$targetTitle = jQuery(targetTitle),
	    	$input = jQuery(input);



	    jQuery.ajax({
	        url: request,
	        data: requestData, // get requested value from parent drop-down field
	        async: true,
	        type: 'POST',
	        success: function(result){
	            list = result[responseName];
	            // Create and show dependent select options            
	            if (list) {
	                jQuery.each(list, function(key, value){
	                    if (typeof value == 'string') {
	                        var values = value.split(': ');
	                        if (values[1].indexOf(selected) >= 0 && selected.length > 0) {
	                            optionList += "<option selected='selected' value = " + values[1] + " >" + values[0] + "</option>";
	                        } else {
	                            optionList += "<option value = " + values[1] + " >" + values[0] + "</option>";
	                        }
	                    } else {
	                        if (value[keyName] == selected) {
	                            optionList += "<option selected='selected' value = " + value[keyName] + " >" + value[descName] + "</option>";
	                        } else {
	                            optionList += "<option value = " + value[keyName] + " >" + value[descName] + "</option>";
	                        }
	                    }
	                })
	            }else{
	            	 optionList += "<option value = '_NA_' >&#4e0d;&#53ef;&#7528;</option>";  <#-- ������ -->
	            }
	            // Hide/show the dependent drop-down if hide=true else simply disable/enable
	            if ((!list) || (list.length < 1) || ((list.length == 1) && jQuery.inArray("_NA_", list) != -1)) {
	                $target.attr('disabled', 'disabled');
	                if (hide) {
	                    if ($target.is(':visible')) {
	                        $target.fadeOut(2500);
	                        if (hideTitle) $targetTitle.fadeOut(2500);
	                    } else {
	                        $target.fadeIn();
	                        if (hideTitle) $targetTitle.fadeIn();
	                        $target.fadeOut(2500);
	                        if (hideTitle) $targetTitle.fadeOut(2500);
	                    }
	                }
	            } else {
	                $target.removeAttr('disabled');
	                if (hide) {
	                    if (!$target.is(':visible')) {
	                        $target.fadeIn();
	                        if (hideTitle) $targetTitle.fadeIn();
	                    }
	                }
	            }
	        },
	        complete: function(){
	            if (!list && inputField) {
	                $target.hide();
	                $input.show();
	            } else if (inputField) {
	                $input.hide();
	                $target.show();
	            }
	            $target.html(optionList).click().change(); // .change() needed when using also asmselect on same field, .click() specifically for IE8
	            if (callback != null) eval(callback);
	        }
	    });
	}



  
		var geoCountryId =  "EditCloudCardStore_${countryFieldName!'geoCountry'}";
		var $geoCountry = jQuery('#' + geoCountryId);

		var geoProvinceId =  "EditCloudCardStore_${provinceFieldName!'geoProvince'}";
		var $geoProvince = jQuery('#' + geoProvinceId);

		var geoCityId = "EditCloudCardStore_${cityFieldName!'geoCity'}";
		var $geoCity = jQuery('#' + geoCityId);

		var geoCountyId = "EditCloudCardStore_${countyFieldName!'geoCounty'}";
		var $geoCounty = jQuery('#' + geoCountyId);



		$geoCountry.change( function() {
			getDependentDropdownValuesColoudCard('${requestName}', 
		    	{'geoIdFrom': $geoCountry.val()}, 
		    	geoProvinceId, 'geoList', 'geoId', 'geoName', ${selectedProvince!'null'});
		});

		$geoProvince.change( function() {
			getDependentDropdownValuesColoudCard('${requestName}', 
		    	{
		    		'geoIdFrom': $geoProvince.val(),
		    		'geoAssocTypeId': 'PROVINCE_CITY'

		    	}, 
		    	geoCityId, 'geoList', 'geoId', 'geoName', ${selectedCity!'null'});
		});

		$geoCity.change( function() {
			getDependentDropdownValuesColoudCard('${requestName}', 
		    	{
		    		'geoIdFrom': $geoCity.val(),
		    		'geoAssocTypeId': 'CITY_COUNTY'

		    	}, 
		    	geoCountyId, 'geoList', 'geoId', 'geoName', ${selectedCounty!'null'});
		});


		
		
		getDependentDropdownValuesColoudCard('${requestName}', {'geoIdFrom': $geoCountry.val()}, 
        	geoProvinceId, 'geoList', 'geoId', 'geoName', ${selectedProvince!'null'});
    

  
})
</script>
