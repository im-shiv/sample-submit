/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 ~ Copyright 2025 Adobe
 ~
 ~ Licensed under the Apache License, Version 2.0 (the "License");
 ~ you may not use this file except in compliance with the License.
 ~ You may obtain a copy of the License at
 ~
 ~     http://www.apache.org/licenses/LICENSE-2.0
 ~
 ~ Unless required by applicable law or agreed to in writing, software
 ~ distributed under the License is distributed on an "AS IS" BASIS,
 ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ~ See the License for the specific language governing permissions and
 ~ limitations under the License.
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/
package com.sample.submit.core.service;

import com.adobe.aemds.guide.model.FormSubmitInfo;
import com.adobe.aemds.guide.service.FormSubmitActionService;
import com.adobe.aemds.guide.service.GuideModelTransformer;
import com.adobe.aemds.guide.utils.GuideConstants;
import com.adobe.aemds.guide.utils.GuideModelUtils;
import com.adobe.aemds.guide.utils.JSONCreationOptions;
import com.adobe.aemds.guide.utils.XMLUtils;
import com.adobe.aemfd.docmanager.Document;
import com.adobe.fd.output.api.OutputService;
import com.adobe.fd.output.api.OutputServiceException;
import com.adobe.fd.output.api.PDFOutputOptions;
import com.adobe.forms.common.service.FileAttachmentWrapper;
import com.adobe.granite.resourceresolverhelper.ResourceResolverHelper;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.ByteArrayBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.osgi.services.HttpClientBuilderFactory;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.commons.json.JSONException;
import org.apache.sling.commons.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Custom form submission service for Document of Record (DoR) generation and REST submissions
 * @author Adobe
 * @version 1.0
 * @since 2025
 * @description Handles DoR PDF generation using XFA templates and submits form data to REST endpoints
 * @see FormSubmitActionService
 */
@Component(service = FormSubmitActionService.class, immediate = true)
public class NBCustomSubmitService implements FormSubmitActionService {

    private static final String serviceName = "NB_CUSTOM_SUBMIT";
    private static final String DEFAULT_LOCALE = "en";
    private static final String DOR_PDF_PREFIX = "dor_";
    private static final String PDF_EXTENSION = ".pdf";
    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private static final Logger logger = LoggerFactory.getLogger(NBCustomSubmitService.class);

    @Reference
    private HttpClientBuilderFactory httpClientBuilderFactory;

    @Reference
    private ResourceResolverHelper resourceResolverHelper;

    @Reference
    private OutputService pdfOutputService;

    @Reference
    private volatile GuideModelTransformer guideModelTransformer;

    @Override
    public String getServiceName() {
        return serviceName;
    }

    /**
     * Main form submission method
     * @param formSubmitInfo The form submission information
     * @return Map containing submission results and status
     * @throws RuntimeException if critical errors occur during processing
     */
    @Override
    public Map<String, Object> submit(FormSubmitInfo formSubmitInfo) {
        Map<String, Object> result = new HashMap<>();
        try {
            Resource formContainerResource = formSubmitInfo.getFormContainerResource();
            ResourceResolver resourceResolver = formContainerResource.getResourceResolver();
            String dorType = formContainerResource.getValueMap().get("dorType", String.class);
            Locale locale = new Locale(formSubmitInfo.getLocale());
            String dorData = processDorData(formSubmitInfo.getData(), locale, formContainerResource);
            String dorTemplateRef = processDorTemplateRef(formContainerResource.getValueMap().get("dorTemplateRef", String.class), locale, resourceResolver, formContainerResource);

            if (StringUtils.isNotBlank(dorType) && StringUtils.equalsIgnoreCase(dorType, "select") && StringUtils.isNotBlank(dorTemplateRef)) {
                final String finalDorTemplateRef = dorTemplateRef;
                final String finalDorData = dorData;
                byte[] pdfContent = resourceResolverHelper.callWith(resourceResolver, () -> getPdfFromXfaDom(finalDorTemplateRef, finalDorData));
                if (pdfContent != null) {
                    String pdfName = DOR_PDF_PREFIX + locale.getLanguage() + PDF_EXTENSION;
                    formSubmitInfo.setDocumentOfRecord(new FileAttachmentWrapper(pdfName, PDF_CONTENT_TYPE, pdfContent));
                }
            }
            result = restSubmit(formContainerResource, formSubmitInfo);
        } catch (Exception e) {
            logger.error("[AF] [Submit] Failed to submit form for the config specified {}", formSubmitInfo, e);
            result.put(GuideConstants.FORM_SUBMISSION_COMPLETE, Boolean.FALSE);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * Processes form data to generate Document of Record XML
     * @param data The raw form data as XML string
     * @param locale The locale for processing
     * @param formContainerResource The form container resource
     * @return Processed Document of Record data as XML string
     * @throws ParserConfigurationException if XML parser configuration fails
     * @throws IOException if data reading/writing fails
     * @throws SAXException if XML parsing fails
     * @throws JSONException if JSON processing fails
     */
    private String processDorData(String data, Locale locale, Resource formContainerResource) throws ParserConfigurationException, IOException, SAXException, JSONException {
        if (StringUtils.isBlank(data)) {
            logger.warn("[AF] [Submit] Empty data provided for DoR processing");
            return "";
        }

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        XMLUtils.disableExternalEntities(dbf);
        DocumentBuilder db = dbf.newDocumentBuilder();
        org.w3c.dom.Document dataDoc = db.parse(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
        org.w3c.dom.Document dorDoc = dbf.newDocumentBuilder().newDocument();

        JSONCreationOptions jsonCreationOptions = new JSONCreationOptions();
        jsonCreationOptions.setFormContainerPath(formContainerResource.getPath());
        jsonCreationOptions.setIncludeFragmentJson(true);
        jsonCreationOptions.setLocale(locale);
        JSONObject guideJSON = guideModelTransformer.exportGuideJsonObject(formContainerResource, jsonCreationOptions);
        return GuideModelUtils.getDataForDORMerge(dorDoc, dataDoc, guideJSON);
    }

    /**
     * Submits form data to configured REST endpoint
     * @param formContainerResource The form container resource
     * @param formSubmitInfo The form submission information
     * @return Map containing submission results and status
     */
    private Map<String, Object> restSubmit(Resource formContainerResource, FormSubmitInfo formSubmitInfo) {
        Map<String, Object> result = new HashMap<>();
        result.put(GuideConstants.FORM_SUBMISSION_COMPLETE, Boolean.FALSE);
        String formContainerResourcePath = formContainerResource != null ? formContainerResource.getPath() : "<Container Resource is null>";

        HttpClientBuilder httpClientBuilder = httpClientBuilderFactory.newBuilder();
        try (CloseableHttpClient httpClient = httpClientBuilder.build()) {
            ValueMap properties = formContainerResource.getValueMap();
            String postUrl = (String) properties.get("postUrl");
            if (StringUtils.isNotBlank(postUrl)) {
                HttpPost httppost = new HttpPost(postUrl);
                MultipartEntityBuilder multipartEntityBuilder = MultipartEntityBuilder.create();
                ContentType contentType = ContentType.TEXT_PLAIN.withCharset("UTF-8"); // using UTF-8 content type to account for non-english characters in data
                multipartEntityBuilder.addTextBody(GuideConstants.JCR_DATA, formSubmitInfo.getData(), contentType);
                multipartEntityBuilder.addTextBody(GuideConstants.DATA_XML, formSubmitInfo.getData(), contentType);
                FileAttachmentWrapper pdfFile = formSubmitInfo.getDocumentOfRecord();
                if (pdfFile != null) {
                    byte[] pdfFileBytes = IOUtils.toByteArray(pdfFile.getInputStream());
                    multipartEntityBuilder.addPart(GuideConstants.ATTACHMENTS, new ByteArrayBody(pdfFileBytes, ContentType.create(pdfFile.getContentType(), StandardCharsets.UTF_8), pdfFile.getFileName()));
                } else {
                    logger.debug("[AF] [Submit] RESTSubmitActionService: pdf file is null for form {}", formContainerResourcePath);
                }

                httppost.setEntity(multipartEntityBuilder.build());
                HttpResponse resp = httpClient.execute(httppost);
                int status = resp.getStatusLine().getStatusCode();
                if (status == HttpStatus.SC_OK) {
                    result.put(GuideConstants.FORM_SUBMISSION_COMPLETE, Boolean.TRUE);
                } else {
                    result.put("FormSubmissionError", "request failed");
                }
            } else {
                result.put(GuideConstants.FORM_SUBMISSION_COMPLETE, Boolean.TRUE);
                logger.debug("[AF] [Submit] RESTSubmitActionService: Rest end point post URL is not set in form {}", formContainerResourcePath);
            }
        } catch (Exception e) {
            logger.error("[AF] [Submit] Failed to make REST call in form {}", formContainerResourcePath, e);
        }
        return result;
    }

    /**
     * Generates PDF from XFA template and data using Adobe Output Service
     * @param xdpRef The XFA template reference path
     * @param data The XML data to merge with the template
     * @return Byte array containing the generated PDF content, or null if generation fails
     * @throws OutputServiceException if PDF generation fails
     * @throws IOException if data processing fails
     */
    private byte[] getPdfFromXfaDom(String xdpRef, String data) throws OutputServiceException, IOException {
        if (xdpRef != null && xdpRef.length() > 0) {
            String contentRootTemp = StringUtils.substringBeforeLast(xdpRef, ".xdp");
            String contentRoot = GuideConstants.PROTOCOL_CRX + contentRootTemp.substring(0, contentRootTemp.lastIndexOf("/"));
            PDFOutputOptions options = new PDFOutputOptions();
            options.setContentRoot(contentRoot);
            Document dataXml = new Document(data.getBytes());
            Document templateXdp = new Document(xdpRef);
            Document renderedPDF = pdfOutputService.generatePDFOutput(templateXdp, dataXml, options);
            if(renderedPDF != null && renderedPDF.getInputStream() != null){
                return IOUtils.toByteArray(renderedPDF.getInputStream());
            } else {
                logger.error("Failed to generate PDF from XFA DOM");
            }
        } else {
            logger.error("No XDP present");
        }
        return null;
    }

    /**
     * Processes and validates the Document of Record template reference
     * @param defaultTemplateRef The original template reference path
     * @param locale The locale for template localization
     * @param resourceResolver The resource resolver to access JCR
     * @param formContainerResource The form container resource
     * @return The validated template reference path if found in JCR, null otherwise
     */
    private String processDorTemplateRef(String defaultTemplateRef, Locale locale, ResourceResolver resourceResolver, Resource formContainerResource) {
        if (StringUtils.isBlank(defaultTemplateRef) || resourceResolver == null || formContainerResource == null || formContainerResource.getParent() == null) {
            logger.debug("[AF] [Submit] Invalid parameters for template reference processing");
            return null;
        }

        try {
            String defaultLocale = formContainerResource.getParent().getValueMap().get("jcr:language", String.class);
            if (StringUtils.isBlank(defaultLocale)) {
                defaultLocale = DEFAULT_LOCALE;
            }

            String localeTemplateRef = defaultTemplateRef;
            if (StringUtils.isNotBlank(locale.getLanguage())) {
                localeTemplateRef = localeTemplateRef.replace("_" + defaultLocale, "_" + locale.getLanguage());
            }

            // Check if the localized resource exists in JCR
            if (resourceResolver.getResource(localeTemplateRef) != null) {
                logger.debug("[AF] [Submit] Localized document found in JCR: {}", localeTemplateRef);
                return localeTemplateRef;
            } else if (resourceResolver.getResource(defaultTemplateRef) != null) {
                logger.debug("[AF] [Submit] Default document found in JCR: {}", defaultTemplateRef);
                return defaultTemplateRef;
            } else {
                logger.warn("[AF] [Submit] No document found in JCR for paths: {} or {}", localeTemplateRef, defaultTemplateRef);
                return null;
            }
        } catch (Exception e) {
            logger.error("[AF] [Submit] Error checking document existence in JCR for path: {}", defaultTemplateRef, e);
            return null;
        }
    }
}
