<%------------------------------------------------------------------------
 ~
 ~ ADOBE CONFIDENTIAL
 ~ __________________
 ~
 ~  Copyright 2025 Adobe Systems Incorporated
 ~  All Rights Reserved.
 ~
 ~ NOTICE:  All information contained herein is, and remains
 ~ the property of Adobe Systems Incorporated and its suppliers,
 ~ if any.  The intellectual and technical concepts contained
 ~ herein are proprietary to Adobe Systems Incorporated and its
 ~ suppliers and may be covered by U.S. and Foreign Patents,
 ~ patents in process, and are protected by trade secret or copyright law.
 ~ Dissemination of this information or reproduction of this material
 ~ is strictly forbidden unless prior written permission is obtained
 ~ from Adobe Systems Incorporated.
 --------------------------------------------------------------------------%>
<%@include file="/libs/fd/af/components/guidesglobal.jsp" %>
<%@page import="com.adobe.aemds.guide.model.FormSubmitInfo,
                com.adobe.aemds.guide.service.FormSubmitActionManagerService,
                com.adobe.aemds.guide.utils.GuideConstants,
                com.adobe.aemds.guide.utils.GuideSubmitUtils,
                java.util.HashMap,
                java.util.Map" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.0" %>
<%@taglib prefix="cq" uri="http://www.day.com/taglibs/cq/1.0" %>
<%
    Map<String,String> redirectParameters;
    redirectParameters = GuideSubmitUtils.getRedirectParameters(slingRequest);
    if(redirectParameters==null) {
        redirectParameters = new HashMap<>();
    }
    FormSubmitActionManagerService submitActionServiceManager = sling.getService(FormSubmitActionManagerService.class);
    FormSubmitInfo formSubmitInfo = (FormSubmitInfo) request.getAttribute(GuideConstants.FORM_SUBMIT_INFO);
    Map<String, Object> resultMap = submitActionServiceManager.submit(formSubmitInfo, Boolean.FALSE);
    GuideSubmitUtils.handleValidationError(request, response, resultMap);
    GuideSubmitUtils.setRedirectParameters(slingRequest,redirectParameters);
%>