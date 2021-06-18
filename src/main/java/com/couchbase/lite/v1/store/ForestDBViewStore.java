/**
 * Created by Hideki Itakura on 10/20/2015.
 * Copyright (c) 2015 Couchbase, Inc All rights reserved.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.couchbase.lite.v1.store;

import com.couchbase.cbforest.Constants;
import com.couchbase.cbforest.Database;
import com.couchbase.cbforest.Document;
import com.couchbase.cbforest.DocumentIterator;
import com.couchbase.cbforest.ForestException;
import com.couchbase.cbforest.Indexer;
import com.couchbase.cbforest.QueryIterator;
import com.couchbase.cbforest.View;
import com.couchbase.lite.v1.CouchbaseLiteException;
import com.couchbase.lite.v1.Emitter;
import com.couchbase.lite.v1.Manager;
import com.couchbase.lite.v1.Mapper;
import com.couchbase.lite.v1.Misc;
import com.couchbase.lite.v1.Predicate;
import com.couchbase.lite.v1.QueryOptions;
import com.couchbase.lite.v1.QueryRow;
import com.couchbase.lite.v1.Reducer;
import com.couchbase.lite.v1.Status;
import com.couchbase.lite.v1.internal.RevisionInternal;
import com.couchbase.lite.v1.store.QueryRowStore;
import com.couchbase.lite.v1.store.ViewStore;
import com.couchbase.lite.v1.store.ViewStoreDelegate;
import com.couchbase.lite.v1.support.action.Action;
import com.couchbase.lite.v1.support.action.ActionBlock;
import com.couchbase.lite.v1.support.action.ActionException;
import com.couchbase.lite.v1.support.security.SymmetricKey;
import com.couchbase.lite.v1.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ForestDBViewStore implements ViewStore, QueryRowStore, Constants {
    public static String TAG = Log.TAG_VIEW;

    public static final String kViewIndexPathExtension = "viewindex";
    private static final Pattern kViewNameRegex = Pattern.compile("^(.*)\\.viewindex(.\\d+)?$");

    // Close the index db after it's inactive this many seconds
    private static final Float kCloseDelay = 60.0f;

    private static final int REDUCE_BATCH_SIZE = 100;

    // lock for updateIndexes method
    private final Object lockUpdateIndexes = new Object();

    ///////////////////////////////////////////////////////////////////////////
    // ForestDBViewStore
    ///////////////////////////////////////////////////////////////////////////

    // public
    private String name;
    private ViewStoreDelegate delegate;

    // private
    private ForestDBStore _dbStore;
    private String _path;
    private View _view;

    ///////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////

    protected ForestDBViewStore(ForestDBStore dbStore, String name, boolean create)
            throws CouchbaseLiteException {
        this._dbStore = dbStore;
        this.name = name;
        this._path = new File(dbStore.directory, viewNameToFileName(name)).getPath();

        // Somewhat of a hack: There probably won't be a file at the exact _path because ForestDB
        // likes to append ".0" etc., but there will be a file with a ".meta" extension:
        File metaFile = new File(this._path + ".meta");
        if (!metaFile.exists()) {
            // migration: CBL Android/Java specific
            {
                // NOTE: .0, .1, etc is created by forestdb if auto compact is enabled.
                // renaming forestdb file name with .0 etc with different name could cause problem.
                // Following migration could work because forestdb filename is without .0 etc.
                // Once filename has .0 etc, do not rename file.

                // if old index file exists, rename it to new name
                File file = new File(this._path);
                File oldFile = new File(dbStore.directory, oldViewNameToFileName(name));
                if (oldFile.exists() && !oldFile.equals(file)) {
                    if (oldFile.renameTo(file))
                        return;
                    // if fail to rename, delete it and create new one from scratch.
                    oldFile.delete();
                }
            }
            if (!create)
                throw new CouchbaseLiteException(Status.NOT_FOUND);
            try {
                openIndex(Database.Create, true);
            } catch (ForestException e) {
                throw new CouchbaseLiteException(e.code);
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Implementation of ViewStorage
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public boolean rowValueIsEntireDoc(byte[] valueData) {
        return false;
    }

    @Override
    public Object parseRowValue(byte[] valueData) {
        return null;
    }

    @Override
    public Map<String, Object> getDocumentProperties(String docID, long sequence) {
        return null;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ViewStoreDelegate getDelegate() {
        return delegate;
    }

    @Override
    public void setDelegate(ViewStoreDelegate delegate) {
        this.delegate = delegate;
    }

    @Override
    public void close() {
        closeIndex();
    }

    @Override
    public void deleteIndex() {
        if (_view != null) {
            try {
                _view.eraseIndex();
            } catch (ForestException e) {
                Log.e(TAG, "Failed to eraseIndex: " + _view);
            }
        }
    }

    @Override
    public void deleteView() {
        deleteViewFiles();
    }

    @Override
    public boolean setVersion(String version) {
        closeIndex();
        return true;
    }

    @Override
    public int getTotalRows() {
        try {
            openIndex();
        } catch (ForestException e) {
            Log.e(TAG, "Exception opening index while getting total rows", e);
            return 0;
        }
        return (int) _view.getTotalRows();
    }

    @Override
    public long getLastSequenceIndexed() {
        try {
            openIndex(); // in case the _mapVersion changed, invalidating the _view
        } catch (ForestException e) {
            Log.e(TAG, "Exception opening index while getting last sequence indexed", e);
            return -1;
        }
        return _view.getLastSequenceIndexed();
    }

    @Override
    public long getLastSequenceChangedAt() {
        try {
            openIndex(); // in case the _mapVersion changed, invalidating the _view
        } catch (ForestException e) {
            Log.e(TAG, "Exception opening index while getting last sequence changed at", e);
            return -1;
        }
        return _view.getLastSequenceChangedAt();
    }

    /**
     * NOTE: updateIndexes() is not thread-safe without synchronized.
     * see https://github.com/couchbase/couchbase-lite-java-core/issues/1363
     */
    @Override
    public Status updateIndexes(List<ViewStore> inputViews) throws CouchbaseLiteException {
        synchronized (lockUpdateIndexes) {
            assert (inputViews != null);

            // workaround
            if (!inputViews.contains(this))
                inputViews.add(this);

            final ArrayList<View> views = new ArrayList<View>(inputViews.size());
            final ArrayList<Mapper> mapBlocks = new ArrayList<Mapper>(inputViews.size());
            final ArrayList<String> docTypes = new ArrayList<String>(inputViews.size());
            boolean useDocType = false;
            for (ViewStore v : inputViews) {
                ForestDBViewStore view = (ForestDBViewStore) v;
                ViewStoreDelegate delegate = view.getDelegate();
                Mapper map = delegate != null ? delegate.getMap() : null;
                if (map == null) {
                    Log.v(Log.TAG_VIEW, "    %s has no map block; skipping it", view.getName());
                    continue;
                }
                try {
                    view.openIndex();
                } catch (ForestException e) {
                    throw new CouchbaseLiteException(ForestBridge.err2status(e));
                }
                views.add(view._view);
                mapBlocks.add(map);
                String docType = delegate.getDocumentType();
                docTypes.add(docType);
                if (docType != null && !useDocType)
                    useDocType = true;
            }

            if (views.size() == 0) {
                Log.v(TAG, "    No input views to update the index");
                return new Status(Status.NOT_MODIFIED);
            }

            boolean success = false;
            Indexer indexer = null;
            try {
                indexer = new Indexer(views.toArray(new View[views.size()]));
                indexer.triggerOnView(this._view);
                DocumentIterator itr;
                try {
                    itr = indexer.iterateDocuments();
                    if (itr == null)
                        return new Status(Status.NOT_MODIFIED);
                } catch (ForestException e) {
                    if (e.code == FDBErrors.FDB_RESULT_SUCCESS)
                        return new Status(Status.NOT_MODIFIED);
                    else
                        throw new CouchbaseLiteException(ForestBridge.err2status(e));
                }
                // Now enumerate the docs:
                Document doc;
                while ((doc = itr.nextDocument()) != null) {
                    // For each updated document:
                    try {
                        String docType = useDocType ? doc.getType() : null;
                        // Skip design docs
                        boolean validDocToIndex =
                                !doc.deleted() && !doc.getDocID().startsWith("_design/");
                        // Read the document body:
                        Map<String, Object> body = ForestBridge.bodyOfSelectedRevision(doc);
                        body.put("_id", doc.getDocID());
                        body.put("_rev", doc.getRevID());
                        body.put("_local_seq", doc.getSequence());
                        if (doc.conflicted()) {
                            List<String> currentRevIDs = ForestBridge.getCurrentRevisionIDs(doc);
                            if (currentRevIDs != null && currentRevIDs.size() > 1)
                                body.put("_conflicts",
                                        currentRevIDs.subList(1, currentRevIDs.size()));
                        }
                        // Feed it to each view's map function:
                        for (int viewNumber = 0; viewNumber < views.size(); viewNumber++) {
                            if (!indexer.shouldIndex(doc, viewNumber))
                                continue;

                            boolean indexIt = validDocToIndex;
                            if (indexIt && useDocType) {
                                String viewDocType = docTypes.get(viewNumber);
                                if (viewDocType != null)
                                    indexIt = viewDocType.equals(docType);
                            }

                            if (indexIt)
                                emit(indexer, viewNumber, doc, body, mapBlocks.get(viewNumber));
                            else
                                emit(indexer, viewNumber, doc, body, null);
                        }
                    } finally {
                        doc.free();
                    }
                }
                success = true;
            } catch (ForestException e) {
                throw new CouchbaseLiteException(ForestBridge.err2status(e));
            } finally {
                if (indexer != null) {
                    try {
                        indexer.endIndex(success);
                    } catch (ForestException ex) {
                        Log.e(TAG, "Failed to call Indexer.endIndex(boolean)", ex);
                        if (success)
                            throw new CouchbaseLiteException(ForestBridge.err2status(ex));
                    }
                }
            }
            Log.v(TAG, "... Finished re-indexing (%s)", viewNames(inputViews));
            return new Status(Status.OK);
        }
    }

    private void emit(Indexer indexer, int viewNumber, Document doc,
                      Map<String, Object> properties, Mapper mapper)
            throws ForestException, CouchbaseLiteException {
        final List<Object> keys = new ArrayList<Object>();
        final List<byte[]> values = new ArrayList<byte[]>();
        if (mapper != null) {
            try {
                // Set up the emit block:
                mapper.map(properties, new Emitter() {
                    @Override
                    public void emit(Object key, Object value) {
                        if (key == null) {
                            Log.w(Log.TAG_VIEW, "emit() called with nil key; ignoring");
                            return;
                        }
                        try {
                            byte[] json = Manager.getObjectMapper().writeValueAsBytes(value);
                            keys.add(key);
                            values.add(json);
                        } catch (Exception e) {
                            Log.e(TAG, "Error in obj -> json", e);
                            throw new RuntimeException(e);
                        }
                    }
                });
            } catch (Throwable e) {
                throw new CouchbaseLiteException(e, Status.CALLBACK_ERROR);
            }
        }
        final byte[][] jsons = new byte[values.size()][];
        for (int i = 0; i < values.size(); i++) {
            jsons[i] = values.get(i);
        }
        indexer.emit(doc, viewNumber, keys.toArray(), jsons);
    }

    @Override
    public List<QueryRow> regularQuery(QueryOptions options) throws CouchbaseLiteException {
        try {
            openIndex();
        } catch (ForestException e) {
            Log.e(TAG, "Exception opening index while getting total rows", e);
            throw new CouchbaseLiteException(e.code);
        }

        final Predicate<QueryRow> postFilter = options.getPostFilter();
        int limit = options.getLimit();
        int skip = options.getSkip();
        if (postFilter != null) {
            // #574: Custom post-filter means skip/limit apply to the filtered rows, not to the
            // underlying query, so handle them specially:
            options.setLimit(QueryOptions.QUERY_OPTIONS_DEFAULT_LIMIT);
            options.setSkip(0);
        }

        List<QueryRow> rows = new ArrayList<QueryRow>();
        QueryIterator itr;
        try {
            itr = forestQuery(options);
            while (itr.next()) {
                RevisionInternal docRevision = null;
                byte[] bKey = itr.keyJSON();
                byte[] bValue = itr.valueJSON();
                Object key = fromJSON(bKey, Object.class);
                Object value = fromJSON(bValue, Object.class);
                String docID = itr.docID();
                long sequence = itr.sequence();
                if (options.isIncludeDocs()) {
                    String linkedID = null;
                    if (value instanceof Map)
                        linkedID = (String) ((Map) value).get("_id");
                    Status status = new Status();
                    if (linkedID != null) {
                        // http://wiki.apache.org/couchdb/Introduction_to_CouchDB_views
                        // #Linked_documents
                        String linkedRev = (String) ((Map) value).get("_rev");
                        docRevision = _dbStore.getDocument(linkedID, linkedRev, true, status);
                        if (docRevision != null)
                            sequence = docRevision.getSequence();
                        else
                            Log.w(TAG, "Couldn't load linked doc %s rev %s: status %d",
                                    linkedID, linkedRev, status.getCode());
                    } else {
                        docRevision = _dbStore.getDocument(docID, null, true, status);
                    }
                }
                Log.v(TAG, "Query %s: Found row with key=%s, value=%s, id=%s",
                        name, key == null ? "" : key, value == null ? "" : value, docID);
                // Create a CBLQueryRow:
                QueryRow row = new QueryRow(docID, sequence,
                        key, value,
                        docRevision);
                if (postFilter != null) {
                    if (!postFilter.apply(row)) {
                        continue;
                    }
                    if (skip > 0) {
                        --skip;
                        continue;
                    }
                }
                rows.add(row);
                if (--limit == 0)
                    break;
            }
        } catch (ForestException e) {
            Log.e(TAG, "Error in regularQuery()", e);
            throw new CouchbaseLiteException(e.code);
        } catch (IOException e) {
            Log.e(TAG, "Error in regularQuery()", e);
            throw new CouchbaseLiteException(Status.UNKNOWN);
        }
        return rows;
    }

    /**
     * Queries the view, with reducing or grouping as per the options.
     * in CBL_ForestDBViewStorage.m
     * - (CBLQueryIteratorBlock) reducedQueryWithOptions: (CBLQueryOptions*)options
     * status: (CBLStatus*)outStatus
     */
    @Override
    public List<QueryRow> reducedQuery(QueryOptions options) throws CouchbaseLiteException {
        Predicate<QueryRow> postFilter = options.getPostFilter();

        int groupLevel = options.getGroupLevel();
        boolean group = options.isGroup() || (groupLevel > 0);
        Reducer reduce = delegate.getReduce();
        if (options.isReduceSpecified()) {
            if (options.isReduce() && reduce == null) {
                Log.w(TAG, "Cannot use reduce option in view %s which has no reduce block defined",
                        name);
                throw new CouchbaseLiteException(new Status(Status.BAD_PARAM));
            }
        }

        final List<Object> keysToReduce = new ArrayList<Object>(REDUCE_BATCH_SIZE);
        final List<Object> valuesToReduce = new ArrayList<Object>(REDUCE_BATCH_SIZE);
        final Object[] lastKeys = new Object[1];
        lastKeys[0] = null;
        final ForestDBViewStore that = this;
        final List<QueryRow> rows = new ArrayList<QueryRow>();

        try {
            openIndex();
        } catch (ForestException e) {
            throw new CouchbaseLiteException(e.code);
        }

        QueryIterator itr;
        try {
            itr = forestQuery(options);

            while (itr.next()) {
                byte[] bKey = itr.keyJSON();
                byte[] bValue = itr.valueJSON();
                Object keyObject = fromJSON(bKey, Object.class);
                Object valueObject = fromJSON(bValue, Object.class);
                if (group && !groupTogether(keyObject, lastKeys[0], groupLevel)) {
                    if (lastKeys[0] != null) {
                        // This pair starts a new group, so reduce & record the last one:
                        Object key = groupKey(lastKeys[0], groupLevel);
                        Object reduced = (reduce != null) ?
                                reduce.reduce(keysToReduce, valuesToReduce, false) : null;
                        QueryRow row = new QueryRow(null, 0, key, reduced, null);
                        if (postFilter == null || postFilter.apply(row))
                            rows.add(row);
                        keysToReduce.clear();
                        valuesToReduce.clear();
                    }
                    lastKeys[0] = keyObject;
                }

                keysToReduce.add(keyObject);
                valuesToReduce.add(valueObject);
            }

        } catch (ForestException e) {
            Log.e(TAG, "Error in reducedQuery()", e);
        } catch (IOException e) {
            Log.e(TAG, "Error in reducedQuery()", e);
            throw new CouchbaseLiteException(Status.UNKNOWN);
        }

        if (keysToReduce != null && keysToReduce.size() > 0) {
            // Finish the last group (or the entire list, if no grouping):
            Object key = group ? groupKey(lastKeys[0], groupLevel) : null;
            Object reduced = (reduce != null) ?
                    reduce.reduce(keysToReduce, valuesToReduce, false) : null;
            Log.v(TAG, String.format(Locale.ENGLISH, "Query %s: Reduced to key=%s, value=%s", name, key, reduced));
            QueryRow row = new QueryRow(null, 0, key, reduced, null);
            if (postFilter == null || postFilter.apply(row))
                rows.add(row);
        }
        return rows;
    }

    @Override
    public List<Map<String, Object>> dump() {
        try {
            openIndex();
        } catch (ForestException e) {
            Log.e(TAG, "ERROR in openIndex()", e);
            return null;
        }

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        try {
            QueryIterator itr = forestQuery(new QueryOptions());
            while (itr.next()) {
                Map<String, Object> dict = new HashMap<String, Object>();
                dict.put("key", new String(itr.keyJSON()));

                byte[] bytes = itr.valueJSON();
                dict.put("value", fromJSON(bytes, Object.class));
                dict.put("seq", itr.sequence());
                result.add(dict);
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error in dump()", ex);
        }
        return result;
    }

    @Override
    public void setCollation(com.couchbase.lite.v1.View.TDViewCollation collation) {
        Log.w(TAG, "This method should be removed");
    }

    ///////////////////////////////////////////////////////////////////////////
    // Internal (Protected/Private)  Methods
    ///////////////////////////////////////////////////////////////////////////

    // Opens the index. You MUST call this (or a method that calls it) before dereferencing _view.
    private View openIndex() throws ForestException {
        return openIndex(0);
    }

    private View openIndex(int flags) throws ForestException {
        return openIndex(flags, false);
    }

    /**
     * Opens the index, specifying ForestDB database flags
     * in CBLView.m
     * - (MapReduceIndex*) openIndexWithOptions: (Database::openFlags)options
     */
    private View openIndex(int flags, boolean dryRun) throws ForestException {
        if (_view == null) {
            // Flags:
            if (_dbStore.getAutoCompact())
                flags |= Database.AutoCompact;

            // Encryption:
            SymmetricKey encryptionKey = _dbStore.getEncryptionKey();
            int enAlgorithm = Database.NoEncryption;
            byte[] enKey = null;
            if (encryptionKey != null) {
                enAlgorithm = Database.AES256Encryption;
                enKey = encryptionKey.getKey();
            }

            _view = new View(_dbStore.forest, _path, flags, enAlgorithm, enKey, name,
                    dryRun ? "0" : delegate.getMapVersion());
            if (dryRun) {
                closeIndex();
            }
        }
        return _view;
    }

    /**
     * in CBL_ForestDBViewStorage.mm
     * - (void) closeIndex
     */
    private void closeIndex() {
        // TODO
        //NSObject cancelPreviousPerformRequestsWithTarget: self selector: @selector(closeIndex) object: nil];

        // NOTE: view could be busy for indexing. as result, view.close() could fail.
        //       It requires to wait till view is not busy. CBL Java/Android waits maximum 10 seconds.
        for (int i = 0; i < 100 && _view != null; i++) {
            try {
                _view.close();
                _view = null;
            } catch (ForestException e) {
                Log.w(TAG, "Failed to close Index: [%s] [%s]", _view, Thread.currentThread().getName());
                try {
                    Thread.sleep(100); // 100 ms (maximum wait time: 10sec)
                } catch (Exception ex) {
                }
            }
        }
    }

    private boolean deleteViewFiles() {
        closeIndex();
        int flags = 0;
        if (_dbStore.getAutoCompact())
            flags |= Database.AutoCompact;
        try {
            View.deleteAtPath(_path, flags);
            return true;
        } catch (ForestException e) {
            Log.e(TAG, "error in deleteAtPath() _path=[%s]", e, _path);
            return false;
        }
    }

    private static String viewNames(List<ViewStore> views) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (ViewStore view : views) {
            if (first)
                first = false;
            else
                sb.append(", ");
            sb.append(view.getName());
        }
        return sb.toString();
    }

    /**
     * Starts a view query, returning a CBForest enumerator.
     * - (C4QueryEnumerator*) _forestQueryWithOptions: (CBLQueryOptions*)options
     * error: (C4Error*)outError
     */
    private QueryIterator forestQuery(QueryOptions options) throws ForestException {
        // NOTE: Geo & FullText queries are not supported yet
        if (options == null)
            options = new QueryOptions();
        long skip = options.getSkip();
        long limit = options.getLimit();
        boolean descending = options.isDescending();
        boolean inclusiveStart = options.isInclusiveStart();
        boolean inclusiveEnd = options.isInclusiveEnd();
        if (options.getKeys() != null && options.getKeys().size() > 0) {
            Object[] keys = options.getKeys().toArray();
            return _view.query(
                    skip,
                    limit,
                    descending,
                    inclusiveStart,
                    inclusiveEnd,
                    keys);
        } else {
            Object endKey = Misc.keyForPrefixMatch(options.getEndKey(),
                    options.getPrefixMatchLevel());
            Object startKey = options.getStartKey();
            String startKeyDocID = options.getStartKeyDocId();
            String endKeyDocID = options.getEndKeyDocId();
            return _view.query(
                    skip,
                    limit,
                    descending,
                    inclusiveStart,
                    inclusiveEnd,
                    startKey,
                    endKey,
                    startKeyDocID,
                    endKeyDocID);
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Internal (Package) Methods
    ///////////////////////////////////////////////////////////////////////////

    Action getActionToChangeEncryptionKey() {
        Action action = new Action();
        action.add(
                new ActionBlock() {
                    @Override
                    public void execute() throws ActionException {
                        if (!deleteViewFiles()) {
                            throw new ActionException("Cannot delete view files");
                        }
                    }
                },
                new ActionBlock() {
                    @Override
                    public void execute() throws ActionException {
                        try {
                            openIndex(Database.Create);
                        } catch (ForestException e) {
                            throw new ActionException("Cannot open index", e);
                        }
                        closeIndex();
                    }
                }
        );
        return action;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Internal (Protected/Private) Static Methods
    ///////////////////////////////////////////////////////////////////////////

    protected static String oldFileNameToViewName(String fileName) throws CouchbaseLiteException {
        if (!fileName.endsWith(kViewIndexPathExtension) || fileName.startsWith("."))
            throw new CouchbaseLiteException(Status.BAD_PARAM);
        String viewName = fileName.substring(0, fileName.indexOf("."));
        return viewName.replaceAll(":", "/");
    }

    private static String oldViewNameToFileName(String viewName) throws CouchbaseLiteException {
        if (viewName.startsWith(".") || viewName.indexOf(":") > 0)
            throw new CouchbaseLiteException(Status.BAD_PARAM);
        return viewName.replaceAll("/", ":") + "." + kViewIndexPathExtension;
    }

    protected static String fileNameToViewName(String fileName) throws CouchbaseLiteException {
        Matcher m = kViewNameRegex.matcher(fileName);
        if (!m.matches())
            throw new CouchbaseLiteException(Status.BAD_PARAM);
        String viewName = fileName.substring(0, fileName.indexOf("."));
        return unescapeViewName(viewName);
    }

    private static String viewNameToFileName(String viewName) throws CouchbaseLiteException {
        if (viewName.startsWith(".") || viewName.indexOf(":") > 0)
            throw new CouchbaseLiteException(Status.BAD_PARAM);
        return escapeViewName(viewName) + "." + kViewIndexPathExtension;
    }

    private static String escapeViewName(String viewName) throws CouchbaseLiteException {
        try {
            viewName = URLEncoder.encode(viewName, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            Log.w(TAG, "Error to url decode: " + viewName, e);
            throw new CouchbaseLiteException(e, Status.BAD_ENCODING);
        }
        return viewName.replaceAll("\\*", "%2A");
    }

    private static String unescapeViewName(String viewName) throws CouchbaseLiteException {
        viewName = viewName.replaceAll("%2A", "*");
        try {
            return URLDecoder.decode(viewName, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            Log.w(TAG, "Error to url decode: " + viewName, e);
            throw new CouchbaseLiteException(e, Status.BAD_ENCODING);
        }
    }

    /**
     * Are key1 and key2 grouped together at this groupLevel?
     */
    private static boolean groupTogether(Object key1, Object key2, int groupLevel) {
        if (groupLevel == 0 || !(key1 instanceof List) || !(key2 instanceof List)) {
            return key1.equals(key2);
        }
        @SuppressWarnings("unchecked")
        List<Object> key1List = (List<Object>) key1;
        @SuppressWarnings("unchecked")
        List<Object> key2List = (List<Object>) key2;

        // if either key list is smaller than groupLevel and the key lists are different
        // sizes, they cannot be equal.
        if ((key1List.size() < groupLevel || key2List.size() < groupLevel) &&
                key1List.size() != key2List.size()) {
            return false;
        }

        int end = Math.min(groupLevel, Math.min(key1List.size(), key2List.size()));
        for (int i = 0; i < end; ++i) {
            if (key1List.get(i) != null && !key1List.get(i).equals(key2List.get(i)))
                return false;
            else if (key1List.get(i) == null && key2List.get(i) != null)
                return false;
        }
        return true;
    }

    /**
     * Returns the prefix of the key to use in the result row, at this groupLevel
     */
    public static Object groupKey(Object key, int groupLevel) {
        if (groupLevel > 0 && (key instanceof List) && (((List<Object>) key).size() > groupLevel)) {
            return ((List<Object>) key).subList(0, groupLevel);
        } else {
            return key;
        }
    }

    // helper method
    private static <T> T fromJSON(byte[] src, Class<T> valueType) throws IOException {
        if (src == null)
            return null;
        return Manager.getObjectMapper().readValue(src, valueType);
    }
}
