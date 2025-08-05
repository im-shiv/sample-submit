package com.sample.submit.core.service;

import com.adobe.aemds.guide.model.FormSubmitInfo;
import com.adobe.aemds.guide.service.FormSubmitActionService;
import com.adobe.aemds.guide.service.GuideModelTransformer;
import com.adobe.aemds.guide.utils.*;
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
 * Custom form submission service for handling Document of Record (DoR) generation and REST submissions
 * 
 * @description This service extends the Adobe Forms service to provide custom form submission functionality.
 * It handles the generation of Document of Record PDFs using XFA templates and submits form data
 * to external REST endpoints. The service supports locale-specific template resolution and
 * comprehensive error handling for robust form processing.
 * 
 * <p>The service performs the following key operations:</p>
 * <ul>
 *   <li>Processes form data and generates Document of Record XML</li>
 *   <li>Resolves locale-specific XFA templates from JCR</li>
 *   <li>Generates PDF documents using Adobe Output Service</li>
 *   <li>Submits form data and attachments to configured REST endpoints</li>
 *   <li>Provides comprehensive logging and error handling</li>
 * </ul>
 */
@Component(service = FormSubmitActionService.class, immediate = true)
public class NBCustomSubmitService implements FormSubmitActionService {

    private static final String serviceName = "NB_CUSTOM_SUBMIT";

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
     * Main form submission method that processes form data and generates Document of Record
     * 
     * @description This method is the primary entry point for form submissions. It processes
     * the submitted form data, generates Document of Record XML, resolves locale-specific
     * templates, generates PDF documents, and submits data to configured REST endpoints.
     * 
     * <p>The method performs the following steps:</p>
     * <ol>
     *   <li>Extracts form configuration and data</li>
     *   <li>Processes form data for Document of Record generation</li>
     *   <li>Resolves and validates locale-specific templates</li>
     *   <li>Generates PDF using Adobe Output Service</li>
     *   <li>Submits data to REST endpoint</li>
     * </ol>
     * 
     * @param formSubmitInfo The form submission information containing data and configuration
     * @return Map containing submission results and status information
     * 
     * @throws RuntimeException if critical errors occur during processing
     */
    @Override
    public Map<String, Object> submit(FormSubmitInfo formSubmitInfo) {
        Map<String, Object> result = new HashMap<>();
        try {
            Resource formContainerResource = formSubmitInfo.getFormContainerResource();
            ResourceResolver resourceResolver = formContainerResource.getResourceResolver();
            String dorType = formContainerResource.getValueMap().get("dorType", String.class);
            String locale = formSubmitInfo.getLocale();
            String dorData = processDorData(formSubmitInfo.getData(), formContainerResource);
            String dorTemplateRef = processDorTemplateRef(formContainerResource.getValueMap().get("dorTemplateRef", String.class), locale, resourceResolver, formContainerResource);

            if (StringUtils.isNotBlank(dorType) && StringUtils.equalsIgnoreCase(dorType, "select") && StringUtils.isNotBlank(dorTemplateRef)) {
                final String finalDorTemplateRef = dorTemplateRef;
                final String finalDorData = dorData;
                byte[] pdfContent = resourceResolverHelper.callWith(resourceResolver, () -> getPdfFromXfaDom(finalDorTemplateRef, finalDorData));
                String pdfName = "dor_" + locale + ".pdf";
                formSubmitInfo.setDocumentOfRecord(new FileAttachmentWrapper(pdfName, "application/pdf", pdfContent));
            }
            result = restSubmit(formContainerResource, resourceResolver, formSubmitInfo);
        } catch (Exception e) {
            logger.error("[AF] [Submit] Failed to submit form for the config specified {}", formSubmitInfo, e);
        }
        return result;
    }

    /**
     * Processes form data to generate Document of Record XML
     * 
     * @description This method transforms the submitted form data into a format suitable
     * for Document of Record generation. It parses the XML data, creates a new document
     * structure, and merges the form data with the guide model configuration.
     * 
     * @param data The raw form data as XML string
     * @param formContainerResource The form container resource containing configuration
     * @return Processed Document of Record data as XML string
     * @throws ParserConfigurationException if XML parser configuration fails
     * @throws IOException if data reading/writing fails
     * @throws SAXException if XML parsing fails
     */
    private String processDorData(String data, Resource formContainerResource) throws ParserConfigurationException, IOException, SAXException {
        //We need to get the bound part from the data xml and use it to generate the pdf.
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        XMLUtils.disableExternalEntities(dbf);
        DocumentBuilder db = dbf.newDocumentBuilder();
        org.w3c.dom.Document dataDoc = db.parse(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
        // creating a document object to create Document of record data xml
        org.w3c.dom.Document dorDoc = dbf.newDocumentBuilder().newDocument();

        JSONCreationOptions jsonCreationOptions = new JSONCreationOptions();
        jsonCreationOptions.setFormContainerPath(formContainerResource.getPath());
        jsonCreationOptions.setIncludeFragmentJson(true);
        jsonCreationOptions.setLocale(new Locale("en", "")); // Default locale, can be changed based on requirements
        JSONObject guideJSON = guideModelTransformer.exportGuideJsonObject(formContainerResource, jsonCreationOptions);
        return GuideModelUtils.getDataForDORMerge(dorDoc, dataDoc, guideJSON);
    }

    /**
     * Submits form data to configured REST endpoint
     * 
     * @description This method handles the REST submission of form data and attachments
     * to the configured endpoint. It creates a multipart HTTP request containing
     * the form data, XML data, and PDF attachment (if generated).
     * 
     * @param formContainerResource The form container resource containing configuration
     * @param resourceResolver The resource resolver for JCR operations
     * @param formSubmitInfo The form submission information
     * @return Map containing submission results and status
     */
    private Map<String, Object> restSubmit(Resource formContainerResource, ResourceResolver resourceResolver, FormSubmitInfo formSubmitInfo) {
        Map<String, Object> result = new HashMap<>();
        result.put(GuideConstants.FORM_SUBMISSION_COMPLETE, Boolean.FALSE);
        String formContainerResourcePath = formContainerResource != null ? formContainerResource.getPath() : "<Container Resource is null>";

        HttpClientBuilder httpClientBuilder = httpClientBuilderFactory.newBuilder();
        try (CloseableHttpClient httpclient = httpClientBuilder.build()) {
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
                HttpResponse resp = httpclient.execute(httppost);
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
     * 
     * @description This method uses the Adobe Output Service to generate PDF documents
     * from XFA templates and XML data. It sets up the content root and output options
     * for proper PDF generation.
     * 
     * @param xdpRef The XFA template reference path
     * @param data The XML data to merge with the template
     * @return Byte array containing the generated PDF content, or null if generation fails
     * @throws OutputServiceException if PDF generation fails
     * @throws IOException if data processing fails
     * 
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
     * 
     * @description This method takes a template reference and processes it based on the locale.
     * It first attempts to find the localized version of the template (replacing "_en" with 
     * the locale-specific suffix), then falls back to the original template if the localized 
     * version doesn't exist in JCR.
     * 
     * @param defaultTemplateRef The original template reference path (e.g., "/content/dam/formsanddocuments/template_en.xdp")
     * @param locale The locale string (e.g., "en", "af", "fr") used for template localization
     * @param resourceResolver The resource resolver to access JCR and check resource existence
     * @param formContainerResource The form container resource containing configuration
     * @return The validated template reference path if found in JCR, null otherwise
     * 
     * @example
     * // For locale "af" and template "/content/dam/formsanddocuments/template_en.xdp"
     * // Returns: "/content/dam/formsanddocuments/template_af.xdp" if it exists
     * // Falls back to: "/content/dam/formsanddocuments/template_en.xdp" if localized version doesn't exist
     * // Returns: null if neither exists
     * 
     * @note This method performs JCR existence checks and handles locale-specific template resolution
     */
    private String processDorTemplateRef(String defaultTemplateRef, String locale, ResourceResolver resourceResolver, Resource formContainerResource) {
        if (StringUtils.isBlank(defaultTemplateRef) || resourceResolver == null || formContainerResource == null || formContainerResource.getParent() == null) {
            return null;
        }
        try {
            String defaultLocale = formContainerResource.getParent().getValueMap().get("jcr:language", String.class);
            if (StringUtils.isBlank(defaultLocale)) {
                defaultLocale = "en"; // Default to English if no locale is provided
            }
            String localeTemplateRef = defaultTemplateRef;
            if( StringUtils.isNotBlank(locale) ) {
                localeTemplateRef = localeTemplateRef.replace("_" + defaultLocale, "_" + locale.substring(0, 2));
            }

            // Check if the resource exists in JCR
            if (resourceResolver.getResource(localeTemplateRef) != null) {
                logger.debug("[AF] [Submit] Document found in JCR: {}", localeTemplateRef);
                return localeTemplateRef;
            } else if (resourceResolver.getResource(defaultTemplateRef) != null) {
                return defaultTemplateRef;
            } else {
                return null;
            }
        } catch (Exception e) {
            logger.error("[AF] [Submit] Error checking document existence in JCR for path: {}", defaultTemplateRef, e);
            return null;
        }
    }
}
