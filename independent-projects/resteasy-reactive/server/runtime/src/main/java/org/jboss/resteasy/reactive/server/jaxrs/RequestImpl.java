package org.jboss.resteasy.reactive.server.jaxrs;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;

import org.jboss.resteasy.reactive.common.jaxrs.AbstractResponseBuilder;
import org.jboss.resteasy.reactive.common.util.DateUtil;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;
import org.jboss.resteasy.reactive.server.core.request.ServerDrivenNegotiation;

public class RequestImpl implements Request {

    private final ResteasyReactiveRequestContext requestContext;
    private final String httpMethod;
    private String varyHeader;

    public RequestImpl(ResteasyReactiveRequestContext requestContext) {
        this.requestContext = requestContext;
        this.httpMethod = requestContext.serverRequest().getRequestMethod();
    }

    @Override
    public String getMethod() {
        return requestContext.serverRequest().getRequestMethod();
    }

    private boolean isRfc7232preconditions() {
        return true;//todo: do we need config for this?
    }

    @Override
    public Variant selectVariant(List<Variant> variants) throws IllegalArgumentException {
        if (variants == null || variants.size() == 0)
            throw new IllegalArgumentException("Variant list must not be empty");

        ServerDrivenNegotiation negotiation = new ServerDrivenNegotiation();
        MultivaluedMap<String, String> requestHeaders = requestContext.getHttpHeaders().getRequestHeaders();
        negotiation.setAcceptHeaders(requestHeaders.get(HttpHeaders.ACCEPT));
        negotiation.setAcceptCharsetHeaders(requestHeaders.get(HttpHeaders.ACCEPT_CHARSET));
        negotiation.setAcceptEncodingHeaders(requestHeaders.get(HttpHeaders.ACCEPT_ENCODING));
        negotiation.setAcceptLanguageHeaders(requestHeaders.get(HttpHeaders.ACCEPT_LANGUAGE));

        varyHeader = AbstractResponseBuilder.createVaryHeader(variants);
        requestContext.serverResponse().setResponseHeader(HttpHeaders.VARY, varyHeader);
        //response.getOutputHeaders().add(VARY, varyHeader);
        return negotiation.getBestMatch(variants);
    }

    private List<EntityTag> convertEtag(List<String> tags) {
        ArrayList<EntityTag> result = new ArrayList<EntityTag>();
        for (String tag : tags) {
            String[] split = tag.split(",");
            for (String etag : split) {
                result.add(EntityTag.valueOf(etag.trim()));
            }
        }
        return result;
    }

    private Response.ResponseBuilder ifMatch(List<EntityTag> ifMatch, EntityTag eTag) {
        boolean match = false;
        for (EntityTag tag : ifMatch) {
            if (tag.equals(eTag) || tag.getValue().equals("*")) {
                match = true;
                break;
            }
        }
        if (match)
            return null;
        return Response.status(Response.Status.PRECONDITION_FAILED).tag(eTag);

    }

    private Response.ResponseBuilder ifNoneMatch(List<EntityTag> ifMatch, EntityTag eTag) {
        boolean match = false;
        for (EntityTag tag : ifMatch) {
            if (tag.equals(eTag) || tag.getValue().equals("*")) {
                match = true;
                break;
            }
        }
        if (match) {
            if ("GET".equals(httpMethod) || "HEAD".equals(httpMethod)) {
                return Response.notModified(eTag);
            }

            return Response.status(Response.Status.PRECONDITION_FAILED).tag(eTag);
        }
        return null;
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions(EntityTag eTag) {
        if (eTag == null)
            throw new IllegalArgumentException("ETag was null");
        Response.ResponseBuilder builder = null;
        List<String> ifMatch = requestContext.getHttpHeaders().getRequestHeaders().get(HttpHeaders.IF_MATCH);
        if (ifMatch != null && ifMatch.size() > 0) {
            builder = ifMatch(convertEtag(ifMatch), eTag);
        }
        if (builder == null) {
            List<String> ifNoneMatch = requestContext.getHttpHeaders().getRequestHeaders().get(HttpHeaders.IF_NONE_MATCH);
            if (ifNoneMatch != null && ifNoneMatch.size() > 0) {
                builder = ifNoneMatch(convertEtag(ifNoneMatch), eTag);
            }
        }
        if (builder != null) {
            builder.tag(eTag);
        }
        if (builder != null && varyHeader != null)
            builder.header(HttpHeaders.VARY, varyHeader);
        return builder;
    }

    private Response.ResponseBuilder ifModifiedSince(String strDate, Date lastModified) {
        Date date = DateUtil.parseDate(strDate);

        if (date.getTime() >= millisecondsWithSecondsPrecision(lastModified)) {
            return Response.notModified();
        }
        return null;

    }

    private Response.ResponseBuilder ifUnmodifiedSince(String strDate, Date lastModified) {
        Date date = DateUtil.parseDate(strDate);

        if (date.getTime() >= millisecondsWithSecondsPrecision(lastModified)) {
            return null;
        }
        return Response.status(Response.Status.PRECONDITION_FAILED).lastModified(lastModified);

    }

    /**
     * We must compare header dates (seconds-precision) with dates that have the same precision,
     * otherwise they may include milliseconds and they will never match the Last-Modified
     * values that we generate from them (since we drop their milliseconds when we write the headers)
     */
    private long millisecondsWithSecondsPrecision(Date lastModified) {
        return (lastModified.getTime() / 1000) * 1000;
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions(Date lastModified) {
        if (lastModified == null)
            throw new IllegalArgumentException("Param cannot be null");
        Response.ResponseBuilder builder = null;
        MultivaluedMap<String, String> headers = requestContext.getHttpHeaders().getRequestHeaders();
        String ifModifiedSince = headers.getFirst(HttpHeaders.IF_MODIFIED_SINCE);
        if (ifModifiedSince != null && (!isRfc7232preconditions() || (!headers.containsKey(HttpHeaders.IF_NONE_MATCH)))) {
            builder = ifModifiedSince(ifModifiedSince, lastModified);
        }
        if (builder == null) {
            String ifUnmodifiedSince = headers.getFirst(HttpHeaders.IF_UNMODIFIED_SINCE);
            if (ifUnmodifiedSince != null && (!isRfc7232preconditions() || (!headers.containsKey(HttpHeaders.IF_MATCH)))) {
                builder = ifUnmodifiedSince(ifUnmodifiedSince, lastModified);
            }
        }
        if (builder != null && varyHeader != null)
            builder.header(HttpHeaders.VARY, varyHeader);

        return builder;
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions(Date lastModified, EntityTag eTag) {
        if (lastModified == null)
            throw new IllegalArgumentException("Last modified was null");
        if (eTag == null)
            throw new IllegalArgumentException("etag was null");
        Response.ResponseBuilder rtn = null;
        Response.ResponseBuilder lastModifiedBuilder = evaluatePreconditions(lastModified);
        Response.ResponseBuilder etagBuilder = evaluatePreconditions(eTag);
        if (lastModifiedBuilder == null && etagBuilder == null)
            rtn = null;
        else if (lastModifiedBuilder != null && etagBuilder == null)
            rtn = lastModifiedBuilder;
        else if (lastModifiedBuilder == null && etagBuilder != null)
            rtn = etagBuilder;
        else {
            rtn = lastModifiedBuilder;
            rtn.tag(eTag);
        }
        if (rtn != null && varyHeader != null)
            rtn.header(HttpHeaders.VARY, varyHeader);
        return rtn;
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions() {
        List<String> ifMatch = requestContext.getHttpHeaders().getRequestHeaders().get(HttpHeaders.IF_MATCH);
        if (ifMatch == null || ifMatch.size() == 0) {
            return null;
        }

        return Response.status(Response.Status.PRECONDITION_FAILED);
    }

}
