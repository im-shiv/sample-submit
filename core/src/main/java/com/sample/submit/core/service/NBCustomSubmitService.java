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

    @Override
    public Map<String, Object> submit(FormSubmitInfo formSubmitInfo) {
        Map<String, Object> result = new HashMap<>();
        try {
            Resource formContainerResource = formSubmitInfo.getFormContainerResource();
            ResourceResolver resourceResolver = formContainerResource.getResourceResolver();
            String dorType = formContainerResource.getValueMap().get("dorType", String.class);
            String dorTemplateRef = formContainerResource.getValueMap().get("dorTemplateRef", String.class);
            String locale = formSubmitInfo.getLocale();
            String data = formSubmitInfo.getData();
            String dorData = processDorData(data, formContainerResource);

            if (StringUtils.isNotEmpty(dorType) && StringUtils.equalsIgnoreCase(dorType, "select") && StringUtils.isNotEmpty(dorTemplateRef)) {
                if( StringUtils.isNotEmpty(locale) ) {
                    dorTemplateRef = dorTemplateRef.replace("_en", "_" + locale.substring(0, 2));
                }
                final String finalDorTemplateRef = dorTemplateRef;
                final String newDorData = dorData;
                byte[] pdfContent = resourceResolverHelper.callWith(resourceResolver, () -> getPdfFromXfaDom(finalDorTemplateRef, newDorData));
                String pdfName = "dor.pdf";
                formSubmitInfo.setDocumentOfRecord(new FileAttachmentWrapper(pdfName, "application/pdf", pdfContent));
            }
            result = restSubmit(formContainerResource, resourceResolver, formSubmitInfo);
        } catch (Exception e) {
            logger.error("[AF] [Submit] Failed to submit form for the config specified {}", formSubmitInfo, e);
        }
        return result;
    }

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
        } finally {
            if (resourceResolver != null) {
                resourceResolver.close();
            }
        }
        return result;
    }

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
}
