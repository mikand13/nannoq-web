package com.nannoq.tools.web.controllers;

import com.nannoq.tools.repository.models.Cacheable;
import com.nannoq.tools.repository.models.ETagable;
import com.nannoq.tools.repository.models.Model;
import com.nannoq.tools.repository.models.ModelUtils;
import com.nannoq.tools.repository.repository.RedisUtils;
import com.nannoq.tools.repository.repository.Repository;
import com.nannoq.tools.repository.utils.*;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.*;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.RoutingContext;
import io.vertx.redis.RedisClient;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

import static com.nannoq.tools.repository.dynamodb.DynamoDBRepository.PAGINATION_INDEX;
import static com.nannoq.tools.repository.utils.AggregateFunctions.MAX;
import static com.nannoq.tools.repository.utils.AggregateFunctions.MIN;
import static com.nannoq.tools.web.RoutingHelper.denyQuery;
import static com.nannoq.tools.web.RoutingHelper.setStatusCodeAndAbort;
import static com.nannoq.tools.web.RoutingHelper.splitQuery;
import static com.nannoq.tools.web.requestHandlers.RequestLogHandler.REQUEST_PROCESS_TIME_TAG;
import static com.nannoq.tools.web.requestHandlers.RequestLogHandler.addLogMessageToRequestLog;
import static com.nannoq.tools.web.responsehandlers.ResponseLogHandler.BODY_CONTENT_TAG;
import static java.util.stream.Collectors.toList;

/**
 * Created by anders on 01/08/16.
 */
public abstract class RestControllerImpl<E extends ETagable & Model & Cacheable> implements RestController<E> {
    static final Logger logger = LoggerFactory.getLogger(RestControllerImpl.class.getSimpleName());

    public static final String PROJECTION_KEY = "projection";
    public static final String PROJECTION_FIELDS_KEY = "fields";
    public static final String ORDER_BY_KEY = "orderBy";
    public static final String AGGREGATE_KEY = "aggregate";

    public static final String MULTIPLE_IDS_KEY = "ids";
    public static final String PAGING_TOKEN_KEY = "pageToken";
    public static final String END_OF_PAGING_KEY = "END_OF_LIST";

    public static final String CONTROLLER_START_TIME = "controllerStartTimeTag";

    protected final Repository<E> REPOSITORY;
    protected final RedisClient REDIS_CLIENT;
    protected final Class<E> TYPE;
    protected final String COLLECTION;
    protected final Function<RoutingContext, JsonObject> idSupplier;

    protected RestControllerImpl(Class<E> type, JsonObject appConfig, Repository<E> repository,
                                 Function<RoutingContext, JsonObject> idSupplier) {
        this.idSupplier = idSupplier;
        this.REPOSITORY = repository;
        this.TYPE = type;
        this.COLLECTION = buildCollectionName(type.getName());
        this.REDIS_CLIENT = RedisUtils.getRedisClient(Vertx.currentContext().owner(), appConfig);
    }

    private String buildCollectionName(String typeName) {
        char c[] = typeName.toCharArray();
        c[0] += 32;

        return new String(c) + "s";
    }

    @SuppressWarnings("unchecked")
    @Override
    public void performShow(RoutingContext routingContext) {
        if (denyQuery(routingContext)) return;

        long initialProcessNanoTime = System.nanoTime();
        JsonObject id = getAndVerifyId(routingContext);

        if (id.isEmpty()) {
            setStatusCodeAndAbort(400, routingContext, initialProcessNanoTime);
        } else {
            String projectionJson = routingContext.request().getParam(PROJECTION_KEY);
            String[] projections = null;

            if (projectionJson != null) {
                try {
                    JsonObject projection = new JsonObject(projectionJson);
                    JsonArray array = projection.getJsonArray(PROJECTION_FIELDS_KEY, null);

                    if (array != null) {
                        projections = array.stream()
                                .map(Object::toString)
                                .collect(toList()).toArray(new String[0]);

                        if (logger.isDebugEnabled()) {
                            addLogMessageToRequestLog(routingContext, "Projection ready!");
                        }
                    }
                } catch (DecodeException | EncodeException e) {
                    addLogMessageToRequestLog(routingContext, "Unable to parse projections: ", e);

                    projections = null;
                }
            }

            if (logger.isDebugEnabled()) {
                addLogMessageToRequestLog(routingContext, "Show projection: " + Arrays.toString(projections));
            }

            String[] finalProjections = projections;

            if (finalProjections != null && finalProjections.length > 0) {
                String etag = routingContext.request().getHeader(HttpHeaders.IF_NONE_MATCH);

                if (logger.isDebugEnabled()) {
                    addLogMessageToRequestLog(routingContext, "Etag is: " + etag);
                }

                if (etag != null) {
                    String hash = id.getString("hash");
                    String etagKeyBase = TYPE.getSimpleName() + "_" + hash + "/projections";
                    String key = TYPE.getSimpleName() + "_" + hash + "/projections" + Arrays.hashCode(finalProjections);

                    if (logger.isDebugEnabled()) {
                        addLogMessageToRequestLog(routingContext, "Checking etag for show...");
                    }

                    RedisUtils.performJedisWithRetry(REDIS_CLIENT, innerRedis ->
                            innerRedis.hget(etagKeyBase, key, getResult -> {
                                if (getResult.succeeded() && getResult.result() != null &&
                                        getResult.result().equals(etag)) {
                                    unChangedIndex(routingContext);
                                } else {
                                    proceedWithRead(routingContext, id, finalProjections);
                                }
                            }));
                } else {
                    proceedWithRead(routingContext, id, finalProjections);
                }
            } else {
                proceedWithRead(routingContext, id, finalProjections);
            }
        }
    }

    private void proceedWithRead(RoutingContext routingContext, JsonObject id, String[] finalProjections) {
        REPOSITORY.read(id, false, finalProjections, result -> {
            if (result.failed()) {
                if (result.result() == null) {
                    notFoundShow(routingContext);
                } else {
                    failedShow(routingContext, new JsonObject().put("error", "Service Unavailable..."));
                }
            } else {
                String etag = routingContext.request().getHeader(HttpHeaders.IF_NONE_MATCH);
                E item = result.result();

                routingContext.response().putHeader(HttpHeaders.ETAG, item.getEtag());

                if (etag != null && item.getEtag().equalsIgnoreCase(etag)) {
                    unChangedShow(routingContext);
                } else {
                    postShow(routingContext, item, finalProjections == null ? new String[]{} : finalProjections);
                }
            }
        });
    }

    @Override
    public void prepareQuery(RoutingContext routingContext, String customQuery) {
        long initialProcessNanoTime = System.nanoTime();
        routingContext.put(CONTROLLER_START_TIME, initialProcessNanoTime);
        String query = customQuery == null ? routingContext.request().query() : customQuery;

        if (query == null || query.isEmpty()) {
            preProcessQuery(routingContext, new ConcurrentHashMap<>());
        } else {
            Map<String, List<String>> queryMap = splitQuery(query);

            preProcessQuery(routingContext, queryMap);
        }
    }

    @Override
    public void processQuery(RoutingContext routingContext, Map<String, List<String>> queryMap) {
        JsonObject errors = new JsonObject();
        AggregateFunction aggregateFunction = null;
        Map<String, List<FilterParameter<E>>> params = new ConcurrentHashMap<>();
        Queue<OrderByParameter> orderByQueue = new ConcurrentLinkedQueue<>();
        final List<String> aggregateQuery = queryMap.get(AGGREGATE_KEY);
        final String[] indexName = {PAGINATION_INDEX};
        final int[] limit = new int[1];

        if (aggregateQuery != null && queryMap.get(ORDER_BY_KEY) != null) {
            String aggregateJson = aggregateQuery.get(0);

            try {
                aggregateFunction = Json.decodeValue(aggregateJson, AggregateFunction.class);

                if (!(aggregateFunction.getFunction() == MIN || aggregateFunction.getFunction() == MAX)) {
                    routingContext.put(BODY_CONTENT_TAG, new JsonObject().put("aggregate_error",
                            "AVG, SUM and COUNT cannot be performed in conjunction with ordering..."));
                    routingContext.fail(400);

                    return;
                }
            } catch (DecodeException | EncodeException e) {
                addLogMessageToRequestLog(routingContext, "Unable to parse projections", e);

                routingContext.put(BODY_CONTENT_TAG, new JsonObject().put("aggregate_query_error",
                        "Unable to parse json..."));
                routingContext.fail(400);

                return;
            }
        }

        queryMap.remove(PAGING_TOKEN_KEY);
        queryMap.remove(AGGREGATE_KEY);
        Field[] fields = getAllFieldsOnType(TYPE);
        Method[] methods = getAllMethodsOnType(TYPE);

        if (aggregateQuery != null && aggregateFunction == null) {
            String aggregateJson = aggregateQuery.get(0);

            try {
                aggregateFunction = Json.decodeValue(aggregateJson, AggregateFunction.class);
            } catch (DecodeException | EncodeException e) {
                addLogMessageToRequestLog(routingContext, "Unable to parse projections", e);

                routingContext.put(BODY_CONTENT_TAG, new JsonObject().put("aggregate_query_error",
                        "Unable to parse json..."));
                routingContext.fail(400);

                return;
            }
        }

        errors = REPOSITORY.buildParameters(
                queryMap, fields, methods, errors, params, limit, orderByQueue, indexName);

        if (errors.isEmpty()) {
            String projectionJson = routingContext.request().getParam(PROJECTION_KEY);
            String[] projections = null;

            if (projectionJson != null) {
                try {
                    JsonObject projection = new JsonObject(projectionJson);
                    JsonArray array = projection.getJsonArray(PROJECTION_FIELDS_KEY, null);

                    if (array != null) {
                        projections = array.stream().map(Object::toString).collect(toList()).toArray(new String[0]);

                        if (logger.isDebugEnabled()) {
                            addLogMessageToRequestLog(routingContext, "Projection ready!");
                        }
                    }
                } catch (DecodeException | EncodeException e) {
                    logger.error("Unable to parse projections: " + e, e);

                    projections = null;
                }
            }

            if (logger.isDebugEnabled()) {
                addLogMessageToRequestLog(routingContext, "Index projections: " + Arrays.toString(projections));
            }

            postProcessQuery(routingContext, aggregateFunction, orderByQueue, params,
                    projections == null ? new String[]{} : projections,
                    indexName == null ? null : indexName[0], limit[0]);
        } else {
            JsonObject errorObject = new JsonObject();
            errorObject.put("request_errors", errors);

            routingContext.response().setStatusCode(400);
            routingContext.put(BODY_CONTENT_TAG, errorObject);
            routingContext.next();
        }
    }

    @Override
    public void createIdObjectForIndex(RoutingContext routingContext, AggregateFunction aggregateFunction,
                                       Queue<OrderByParameter> orderByQueue, Map<String, List<FilterParameter<E>>> params,
                                       String[] projections, String indexName, Integer limit) {
        JsonObject id = getAndVerifyId(routingContext);

        performIndex(routingContext, id, aggregateFunction, orderByQueue, params, projections, indexName, limit);
    }

    @Override
    public void performIndex(RoutingContext routingContext, JsonObject identifiers, AggregateFunction aggregateFunction,
                             Queue<OrderByParameter> orderByQueue, Map<String, List<FilterParameter<E>>> params,
                             String[] projections, String indexName, Integer limit) {
        long initialProcessNanoTime = routingContext.get(CONTROLLER_START_TIME);
        HttpServerRequest request = routingContext.request();
        String query = routingContext.request().query();
        String pageToken = request.getParam(PAGING_TOKEN_KEY);
        String etag = request.getHeader(HttpHeaders.IF_NONE_MATCH);

        if (request.rawMethod().equalsIgnoreCase("GET")) {
            String idArray = request.getParam(MULTIPLE_IDS_KEY);

            if (idArray != null) {
                try {
                    JsonArray ids = new JsonArray(idArray);
                    identifiers
                            .put("range", ids)
                            .put("multiple", true);

                } catch (DecodeException | EncodeException e) {
                    addLogMessageToRequestLog(routingContext, "Unable to parse projections!", e);

                    routingContext.put(BODY_CONTENT_TAG, new JsonObject().put("ids_query_error",
                            "Unable to parse json..."));
                    routingContext.fail(400);

                    return;
                }
            }
        }

        Boolean multiple = identifiers.getBoolean("multiple");
        JsonArray ids = null;
        if (multiple != null && multiple) ids = identifiers.getJsonArray("range");

        if (multiple != null && multiple && ids != null && ids.isEmpty()) {
            routingContext.put(BODY_CONTENT_TAG, Json.encodePrettily(new JsonObject().put("error",
                    "You cannot request multiple ids with an empty array!")));

            setStatusCodeAndAbort(400, routingContext, initialProcessNanoTime);
        } else {
            if (logger.isDebugEnabled()) {
                addLogMessageToRequestLog(routingContext, "Started index!");
            }

            if (pageToken != null && pageToken.equalsIgnoreCase(END_OF_PAGING_KEY)) {
                routingContext.put(BODY_CONTENT_TAG, Json.encodePrettily(new JsonObject().put("error",
                        "You cannot page for the " + END_OF_PAGING_KEY + ", " +
                                "this message means you have reached the end of the results requested.")));

                setStatusCodeAndAbort(400, routingContext, initialProcessNanoTime);
            } else {
                final String finalQuery = query == null ? null : String.valueOf(query.hashCode());
                final String route = request.path();
                QueryPack<E> queryPack = QueryPack.<E>builder()
                        .withQuery(finalQuery)
                        .withRoute(route)
                        .withRequestEtag(etag)
                        .withOrderByQueue(orderByQueue)
                        .withFilterParameters(params)
                        .withAggregateFunction(aggregateFunction)
                        .withIndexName(indexName)
                        .withLimit(limit)
                        .build();

                if (queryPack.getAggregateFunction() != null) {
                    proceedWithAggregationIndex(routingContext, etag, identifiers, queryPack, projections);
                } else {
                    String hash = identifiers.getString("hash");
                    String etagItemListHashKey = TYPE.getSimpleName() + "_" +
                            (hash != null ? hash + "_" : "") +
                            "itemListEtags";

                    String etagKey = queryPack.getBaseEtagKey();

                    if (logger.isDebugEnabled()) {
                        addLogMessageToRequestLog(routingContext, "Querypack ok, fetching etag for " + etagKey);
                    }

                    final String[] finalProjections = projections;

                    if (etag != null) {
                        RedisUtils.performJedisWithRetry(REDIS_CLIENT, innerRedis ->
                                innerRedis.hget(etagItemListHashKey, etagKey, getResult -> {
                                    if (logger.isDebugEnabled()) {
                                        logger.debug("Stored etag: " + getResult.result() + ", request: " + etag);
                                    }

                                    if (getResult.succeeded() && getResult.result() != null &&
                                            getResult.result().equals(etag)) {
                                        unChangedIndex(routingContext);
                                    } else {
                                        proceedWithPagedIndex(identifiers, pageToken,
                                                queryPack, finalProjections, routingContext);
                                    }
                                }));
                    } else {
                        proceedWithPagedIndex(identifiers, pageToken, queryPack, finalProjections, routingContext);
                    }
                }
            }
        }
    }

    @Override
    public void proceedWithPagedIndex(JsonObject id, String pageToken,
                                      QueryPack<E> queryPack, String[] projections, RoutingContext routingContext) {
        REPOSITORY.readAll(id, pageToken, queryPack, projections, readResult -> {
            if (readResult.failed()) {
                addLogMessageToRequestLog(routingContext, "FAILED: " + (readResult.result() == null ?
                        null : readResult.result().getItems()), readResult.cause());

                failedIndex(routingContext, new JsonObject().put("error", "Service unavailable..."));
            } else {
                ItemList<E> items = readResult.result();

                if (items != null) {
                    routingContext.response().putHeader(HttpHeaders.ETAG, items.getEtag());

                    if (logger.isDebugEnabled()) {
                        addLogMessageToRequestLog(routingContext,
                                "Projections for output is: " + Arrays.toString(projections));
                    }

                    postIndex(routingContext, items, projections);
                } else {
                    addLogMessageToRequestLog(routingContext, "FAILED ITEMS!");

                    failedIndex(routingContext, new JsonObject().put("error", "Returned items is null!"));
                }
            }
        });
    }

    @Override
    public void proceedWithAggregationIndex(RoutingContext routingContext, String etag, JsonObject id,
                                            QueryPack<E> queryPack, String[] projections) {
        if (logger.isDebugEnabled()) {
            addLogMessageToRequestLog(routingContext, "Started aggregation request");
        }

        AggregateFunction function = queryPack.getAggregateFunction();
        String etagKey = null;

        switch (function.getFunction()) {
            case MIN:
                etagKey = queryPack.getBaseEtagKey() + "_" + function.getField() + "_MIN" + function.getGroupBy().hashCode();

                break;
            case MAX:
                etagKey = queryPack.getBaseEtagKey() + "_" + function.getField() + "_MAX" + function.getGroupBy().hashCode();

                break;
            case AVG:
                etagKey = queryPack.getBaseEtagKey() + "_" + function.getField() + "_AVG" + function.getGroupBy().hashCode();

                break;
            case SUM:
                etagKey = queryPack.getBaseEtagKey() + "_" + function.getField() + "_SUM" + function.getGroupBy().hashCode();

                break;
            case COUNT:
                etagKey = queryPack.getBaseEtagKey() + "_COUNT" + function.getGroupBy().hashCode();

                break;
        }

        String finalEtagKey = etagKey;

        if (etag != null) {
            String hash = id.getString("hash");
            String etagItemListHashKey = TYPE.getSimpleName() + "_" +
                    (hash != null ? hash + "_" : "") +
                    "itemListEtags";

            RedisUtils.performJedisWithRetry(REDIS_CLIENT, ir -> ir.hget(etagItemListHashKey, finalEtagKey, getRes -> {
                if (getRes.succeeded() && getRes.result() != null && getRes.result().equals(etag)) {
                    unChangedIndex(routingContext);
                } else {
                    doAggregation(routingContext, id, queryPack, projections);
                }
            }));
        } else {
            doAggregation(routingContext, id, queryPack, projections);
        }
    }

    protected void doAggregation(RoutingContext routingContext, JsonObject id,
                                 QueryPack<E> queryPack, String[] projections) {
        REPOSITORY.aggregation(id, queryPack, projections, readResult -> {
            if (readResult.failed()) {
                addLogMessageToRequestLog(routingContext,
                        "FAILED AGGREGATION: " + Json.encodePrettily(queryPack), readResult.cause());

                failedIndex(routingContext, new JsonObject().put("error", "Aggregation Index failed..."));
            } else {
                String output = readResult.result();

                if (output != null) {
                    String newEtag = ModelUtils.returnNewEtag(output.hashCode());

                    routingContext.response().putHeader(HttpHeaders.ETAG, newEtag);

                    postAggregation(routingContext, output);
                } else {
                    addLogMessageToRequestLog(routingContext, "FAILED AGGREGATION, NULL");

                    failedIndex(routingContext, new JsonObject().put("error", "Aggregation Index failed..."));
                }
            }
        });
    }

    @Override
    public void parseBodyForCreate(RoutingContext routingContext) {
        final long initialProcessNanoTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);

        if (routingContext.getBody().getBytes().length == 0) {
            try {
                preVerifyNotExists(TYPE.newInstance(), routingContext);
            } catch (InstantiationException | IllegalAccessException e) {
                addLogMessageToRequestLog(routingContext, "Unable to create empty body!", e);

                setStatusCodeAndAbort(500, routingContext, initialProcessNanoTime);
            }
        } else {
            try {
                String json = routingContext.getBodyAsString();
                E newRecord = Json.decodeValue(json, TYPE);

                preVerifyNotExists(newRecord, routingContext);
            } catch (DecodeException e) {
                addLogMessageToRequestLog(routingContext, "Unable to parse body!", e);

                setStatusCodeAndAbort(500, routingContext, initialProcessNanoTime);
            }
        }
    }

    @Override
    public void verifyNotExists(E newRecord, RoutingContext routingContext) {
        long initialProcessNanoTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);
        JsonObject id = getAndVerifyId(routingContext);

        try {
            E e = TYPE.newInstance();

            if (e == null) {
                setStatusCodeAndAbort(422, routingContext, initialProcessNanoTime);
            } else {
                e.setInitialValues(newRecord);

                REPOSITORY.read(id, readResult -> {
                    if (readResult.succeeded()) {
                        setStatusCodeAndAbort(409, routingContext, initialProcessNanoTime);
                    } else {
                        postVerifyNotExists(e, routingContext);
                    }
                });
            }
        } catch (InstantiationException | IllegalAccessException ie) {
            addLogMessageToRequestLog(routingContext, "Could not create item!", ie);

            setStatusCodeAndAbort(500, routingContext, initialProcessNanoTime);
        }
    }

    @Override
    public void performCreate(E newRecord, RoutingContext routingContext) {
        REPOSITORY.create(newRecord, result -> {
            if (result.failed()) {
                addLogMessageToRequestLog(routingContext, "Could not create item!", result.cause());

                JsonObject errorObject = new JsonObject()
                        .put("create_error", "Unable to create record...");

                failedCreate(routingContext, errorObject);
            } else {
                E finalRecord = result.result();

                routingContext.response()
                        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
                        .putHeader(HttpHeaders.ETAG, finalRecord.getEtag());

                postCreate(finalRecord, routingContext);
            }
        });
    }

    @Override
    public void parseBodyForUpdate(RoutingContext routingContext) {
        long initialProcessNanoTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);
        String json = routingContext.getBodyAsString();

        if (json == null) {
            setStatusCodeAndAbort(422, routingContext, initialProcessNanoTime);
        } else {
            try {
                E newRecord = Json.decodeValue(json, TYPE);

                preVerifyExistsForUpdate(newRecord, routingContext);
            } catch (DecodeException e) {
                addLogMessageToRequestLog(routingContext, "Unable to parse body!", e);

                setStatusCodeAndAbort(500, routingContext, initialProcessNanoTime);
            }
        }
    }

    @Override
    public void verifyExistsForUpdate(E newRecord, RoutingContext routingContext) {
        long initialProcessNanoTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);
        final JsonObject id = getAndVerifyId(routingContext);

        if (id.isEmpty()) {
            setStatusCodeAndAbort(400, routingContext, initialProcessNanoTime);
        } else {
            REPOSITORY.read(id, readResult -> {
                if (readResult.failed()) {
                    setStatusCodeAndAbort(404, routingContext, initialProcessNanoTime);
                } else {
                    E record = readResult.result();

                    preSanitizeForUpdate(record, newRecord, routingContext);
                }
            });
        }
    }

    @Override
    public void performUpdate(E updatedRecord, Function<E, E> setNewValues, RoutingContext routingContext) {
        REPOSITORY.update(updatedRecord, setNewValues, result -> {
            if (result.failed()) {
                failedUpdate(routingContext, new JsonObject().put("error", "Unable to update record..."));
            } else {
                E finalRecord = result.result();

                routingContext.response()
                        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
                        .putHeader(HttpHeaders.ETAG, finalRecord.getEtag());

                postUpdate(finalRecord, routingContext);
            }
        });
    }

    @Override
    public void verifyExistsForDestroy(RoutingContext routingContext) {
        long initialProcessNanoTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);
        JsonObject id = getAndVerifyId(routingContext);

        if (id.isEmpty()) {
            setStatusCodeAndAbort(400, routingContext, initialProcessNanoTime);
        } else {
            REPOSITORY.read(id, readResult -> {
                if (readResult.failed()) {
                    logger.error("Could not find record!", readResult.cause());

                    setStatusCodeAndAbort(404, routingContext, initialProcessNanoTime);
                } else {
                    postVerifyExistsForDestroy(readResult.result(), routingContext);
                }
            });
        }
    }

    @Override
    public void performDestroy(E recordForDestroy, RoutingContext routingContext) {
        JsonObject id = getAndVerifyId(routingContext);

        REPOSITORY.delete(id, result -> {
            if (result.failed()) {
                failedDestroy(routingContext, new JsonObject().put("error", "Unable to destroy record!"));
            } else {
                E finalRecord = result.result();

                postDestroy(finalRecord, routingContext);
            }
        });
    }

    protected JsonObject getAndVerifyId(RoutingContext routingContext) {
        return idSupplier.apply(routingContext);
    }

    @SuppressWarnings("unused")
    private String buildCollectionEtagKey() {
        return "data_api_" + COLLECTION + "_s_etag";
    }

    public Class getType() {
        return TYPE;
    }
}
