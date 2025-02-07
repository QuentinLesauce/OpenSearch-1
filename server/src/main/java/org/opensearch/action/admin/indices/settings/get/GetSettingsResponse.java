/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.action.admin.indices.settings.get;

import com.carrotsearch.hppc.cursors.ObjectObjectCursor;
import org.opensearch.action.ActionResponse;
import org.opensearch.common.Strings;
import org.opensearch.common.collect.ImmutableOpenMap;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.ToXContent;
import org.opensearch.common.xcontent.ToXContentObject;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.XContentParser;
import org.opensearch.common.xcontent.XContentParserUtils;
import org.opensearch.common.xcontent.json.JsonXContent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class GetSettingsResponse extends ActionResponse implements ToXContentObject {

    private final ImmutableOpenMap<String, Settings> indexToSettings;
    private final ImmutableOpenMap<String, Settings> indexToDefaultSettings;

    public GetSettingsResponse(ImmutableOpenMap<String, Settings> indexToSettings,
                               ImmutableOpenMap<String, Settings> indexToDefaultSettings) {
        this.indexToSettings = indexToSettings;
        this.indexToDefaultSettings = indexToDefaultSettings;
    }

    public GetSettingsResponse(StreamInput in) throws IOException {
        super(in);
        indexToSettings = in.readImmutableMap(StreamInput::readString, Settings::readSettingsFromStream);
        indexToDefaultSettings = in.readImmutableMap(StreamInput::readString, Settings::readSettingsFromStream);
    }

    /**
     * Returns a map of index name to {@link Settings} object.  The returned {@link Settings}
     * objects contain only those settings explicitly set on a given index.  Any settings
     * taking effect as defaults must be accessed via {@link #getIndexToDefaultSettings()}.
     */
    public ImmutableOpenMap<String, Settings> getIndexToSettings() {
        return indexToSettings;
    }

    /**
     * If the originating {@link GetSettingsRequest} object was configured to include
     * defaults, this will contain a mapping of index name to {@link Settings} objects.
     * The returned {@link Settings} objects will contain only those settings taking
     * effect as defaults.  Any settings explicitly set on the index will be available
     * via {@link #getIndexToSettings()}.
     * See also {@link GetSettingsRequest#includeDefaults(boolean)}
     */
    public ImmutableOpenMap<String, Settings> getIndexToDefaultSettings() {
        return indexToDefaultSettings;
    }

    /**
     * Returns the string value for the specified index and setting.  If the includeDefaults
     * flag was not set or set to false on the GetSettingsRequest, this method will only
     * return a value where the setting was explicitly set on the index.  If the includeDefaults
     * flag was set to true on the GetSettingsRequest, this method will fall back to return the default
     * value if the setting was not explicitly set.
     */
    public String getSetting(String index, String setting) {
        Settings settings = indexToSettings.get(index);
        if (setting != null) {
            if (settings != null && settings.hasValue(setting)) {
                return settings.get(setting);
            } else {
                Settings defaultSettings = indexToDefaultSettings.get(index);
                if (defaultSettings != null) {
                    return defaultSettings.get(setting);
                } else {
                    return null;
                }
            }
        } else {
            return null;
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeMap(indexToSettings, StreamOutput::writeString, (o, s) -> Settings.writeSettingsToStream(s, o));
        out.writeMap(indexToDefaultSettings, StreamOutput::writeString, (o, s) -> Settings.writeSettingsToStream(s, o));
    }

    private static void parseSettingsField(XContentParser parser, String currentIndexName, Map<String, Settings> indexToSettings,
                                           Map<String, Settings> indexToDefaultSettings) throws IOException {

            if (parser.currentToken() == XContentParser.Token.START_OBJECT) {
                switch (parser.currentName()) {
                    case "settings":
                        indexToSettings.put(currentIndexName, Settings.fromXContent(parser));
                        break;
                    case "defaults":
                        indexToDefaultSettings.put(currentIndexName, Settings.fromXContent(parser));
                        break;
                    default:
                        parser.skipChildren();
                }
            } else if (parser.currentToken() == XContentParser.Token.START_ARRAY) {
                parser.skipChildren();
            }
            parser.nextToken();
    }

    private static void parseIndexEntry(XContentParser parser, Map<String, Settings> indexToSettings,
    Map<String, Settings> indexToDefaultSettings) throws IOException {
        String indexName = parser.currentName();
        parser.nextToken();
        while (!parser.isClosed() && parser.currentToken() != XContentParser.Token.END_OBJECT) {
            parseSettingsField(parser, indexName, indexToSettings, indexToDefaultSettings);
        }
    }
    public static GetSettingsResponse fromXContent(XContentParser parser) throws IOException {
        HashMap<String, Settings> indexToSettings = new HashMap<>();
        HashMap<String, Settings> indexToDefaultSettings = new HashMap<>();

        if (parser.currentToken() == null) {
            parser.nextToken();
        }
        XContentParserUtils.ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.currentToken(), parser);
        parser.nextToken();

        while (!parser.isClosed()) {
            if (parser.currentToken() == XContentParser.Token.START_OBJECT) {
                //we must assume this is an index entry
                parseIndexEntry(parser, indexToSettings, indexToDefaultSettings);
            } else if (parser.currentToken() == XContentParser.Token.START_ARRAY) {
                parser.skipChildren();
            } else {
                parser.nextToken();
            }
        }

        ImmutableOpenMap<String, Settings> settingsMap = ImmutableOpenMap.<String, Settings>builder().putAll(indexToSettings).build();
        ImmutableOpenMap<String, Settings> defaultSettingsMap =
            ImmutableOpenMap.<String, Settings>builder().putAll(indexToDefaultSettings).build();

        return new GetSettingsResponse(settingsMap, defaultSettingsMap);
    }

    @Override
    public String toString() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            XContentBuilder builder = new XContentBuilder(JsonXContent.jsonXContent, baos);
            toXContent(builder, ToXContent.EMPTY_PARAMS, false);
            return Strings.toString(builder);
        } catch (IOException e) {
            throw new IllegalStateException(e); //should not be possible here
        }
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        return toXContent(builder, params, indexToDefaultSettings.isEmpty());
    }

    private XContentBuilder toXContent(XContentBuilder builder, Params params, boolean omitEmptySettings) throws IOException {
        builder.startObject();
        for (ObjectObjectCursor<String, Settings> cursor : getIndexToSettings()) {
            // no settings, jump over it to shorten the response data
            if (omitEmptySettings && cursor.value.isEmpty()) {
                continue;
            }
            builder.startObject(cursor.key);
            builder.startObject("settings");
            cursor.value.toXContent(builder, params);
            builder.endObject();
            if (indexToDefaultSettings.isEmpty() == false) {
                builder.startObject("defaults");
                indexToDefaultSettings.get(cursor.key).toXContent(builder, params);
                builder.endObject();
            }
            builder.endObject();
        }
        builder.endObject();
        return builder;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GetSettingsResponse that = (GetSettingsResponse) o;
        return Objects.equals(indexToSettings, that.indexToSettings) &&
            Objects.equals(indexToDefaultSettings, that.indexToDefaultSettings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(indexToSettings, indexToDefaultSettings);
    }
}
