package org.schabi.newpipe.extractor.services.bilibili.extractors;

import static org.schabi.newpipe.extractor.services.bilibili.BilibiliService.*;
import static org.schabi.newpipe.extractor.services.bilibili.utils.getNextPageFromCurrentUrl;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelExtractor;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.search.filter.Filter;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.services.bilibili.BilibiliService;
import org.schabi.newpipe.extractor.services.bilibili.DeviceForger;
import org.schabi.newpipe.extractor.services.bilibili.utils;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

public class BilibiliChannelExtractor extends ChannelExtractor {

    JsonObject userInfoData = new JsonObject();
    JsonObject userLiveData = new JsonObject();

    /** Set when the user card request was skipped, see {@link #onFetchPage}. */
    private BilibiliService.ChannelCard knownChannelCard;

    //region User Video Impl

    private final ClientUserVideoImpl clientUserVideoImpl = new ClientUserVideoImpl();
    private final SearchUserVideoImpl searchUserVideoImpl = new SearchUserVideoImpl();
    private final WebUserVideoImpl webUserVideoImpl = new WebUserVideoImpl();


    private UserVideoImpl getVideoImpl() {
        int mode = getCurrentVideoApiMode();
        switch (mode) {
            case USER_VIDEO_API_MODE_WEB:
                return webUserVideoImpl;
            case USER_VIDEO_API_MODE_SEARCH:
                return searchUserVideoImpl;
            case USER_VIDEO_API_MODE_CLIENT:
                return clientUserVideoImpl;
            default:
                rotateVideoApiMode();
                return webUserVideoImpl;
        }
    }
    //endregion

    public BilibiliChannelExtractor(StreamingService service, ListLinkHandler linkHandler) {
        super(service, linkHandler);
    }

    @Override
    public void onFetchPage(@Nonnull Downloader downloader) throws IOException, ExtractionException {
        Map<String, List<String>> headers = getHeaders(getOriginalUrl());
        String id = getId();

        getVideoImpl().onFetchPage(downloader, id, getUrl());

        // A feed refresh tells us what it already knows about this channel, so the user card
        // (one request per channel, per refresh) is only downloaded when it may have changed.
        final BilibiliService.ChannelCard knownCard = BilibiliService.getKnownChannelCard();
        if (knownCard == null || shouldFetchCard(knownCard)) {
            userInfoData = requestUserSpaceResponse(downloader, QUERY_USER_INFO_URL + id, headers);
        } else {
            knownChannelCard = knownCard;
        }

        // The entry this produces is dropped by the feed anyway - it is a round-play item - so a
        // feed refresh, which is the only caller that announces itself, does not spend a request
        // on it. The channel screen still gets its live banner.
        if (BilibiliService.getKnownChannelCard() == null) {
            userLiveData = requestUserSpaceResponse(
                    downloader, QUERY_LIVEROOM_STATUS_URL + id, headers);
        }
    }

    /**
     * Fetches the card when the channel is unknown, when no avatar was ever received, or when
     * the name in the video list no longer matches the name we know.
     */
    private boolean shouldFetchCard(final BilibiliService.ChannelCard known) {
        if (known.name == null || known.avatarUrl == null || known.avatarUrl.isEmpty()) {
            return true;
        }
        final String currentName = uploaderNameFromVideoList();
        return currentName == null || !known.name.equals(currentName);
    }

    /**
     * Name reported by the video list itself, or {@code null} when that mode does not carry one.
     */
    private String uploaderNameFromVideoList() {
        return getVideoImpl().uploaderName();
    }

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        if (isCardSkipped()) {
            // The video list carries the very name the card would have told us.
            final String fromVideoList = uploaderNameFromVideoList();
            if (fromVideoList != null && !fromVideoList.isEmpty()) {
                return fromVideoList;
            }
            if (knownChannelCard != null && knownChannelCard.name != null) {
                return knownChannelCard.name;
            }
            throw new ParsingException("Could not get channel name");
        }
        return userInfoData.getObject("data").getObject("card").getString("name");
    }

    /** True when the user card request was skipped, so its data must not be read. */
    private boolean isCardSkipped() {
        return userInfoData.size() == 0;
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage() throws IOException, ExtractionException {

        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        // Absent when the live status was skipped, see onFetchPage.
        final JsonObject liveData = userLiveData.getObject("data").getObject(getId());
        if (liveData != null && liveData.getInt("live_status") != 0) {
            collector.commit(new BilibiliLiveInfoItemExtractor(liveData, 1));
        }
        UserVideoImpl videoImpl = getVideoImpl();
        boolean hasVideos = videoImpl.getInitialPage(collector, this);
        Page nextPage = null;
        // 0 means the page carried no usable cursor, and a next page built from it would only
        // fetch the first page again.
        final long lastVideo = videoImpl.lastVideo();
        if (hasVideos && lastVideo > 0) nextPage = new Page(getNextPageFromCurrentUrl(
                getUrl(), "pn", 1, true, "1", "?"), String.valueOf(lastVideo), null, BilibiliService.getDefaultCookies(), null
        );
        if (ServiceList.BiliBili.getFilterTypes().contains("channels")) {
            collector.applyBlocking(ServiceList.BiliBili.getFilterConfig());
        }
        return new InfoItemsPage<>(collector, nextPage);
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(Page page) throws IOException, ExtractionException {
        Map<String, List<String>> headers = getHeaders(getOriginalUrl());
        String id = getId();
        Downloader downloader = getDownloader();

        // Same rule as onFetchPage, and it matters more here: a refresh walks several pages per
        // channel, and the card endpoint is the one most likely to answer 412 during a refresh.
        // The hint is what a feed refresh announces, and it already carries everything the card
        // would return.
        final BilibiliService.ChannelCard knownCard = BilibiliService.getKnownChannelCard();
        if (knownCard != null) {
            knownChannelCard = knownCard;
        } else {
            userInfoData = requestUserSpaceResponse(downloader, QUERY_USER_INFO_URL + id, headers);
        }

        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        UserVideoImpl videoImpl = getVideoImpl();
        boolean hasVideos = videoImpl.getPage(page, collector, getDownloader(), this, getId());
        Page nextPage = null;
        final long lastVideo = videoImpl.lastVideo();
        if (hasVideos && lastVideo > 0) nextPage = new Page(
                getNextPageFromCurrentUrl(page.getUrl(), "pn", 1), String.valueOf(lastVideo), null, BilibiliService.getDefaultCookies(), null
        );
        if (ServiceList.BiliBili.getFilterTypes().contains("channels")) {
            collector.applyBlocking(ServiceList.BiliBili.getFilterConfig());
        }
        return new InfoItemsPage<>(collector, nextPage);
    }

    @Override
    public String getAvatarUrl() throws ParsingException {
        if (isCardSkipped()) {
            // Whatever we knew is handed back unchanged, so persisting it stays a no-op.
            return knownChannelCard != null ? knownChannelCard.avatarUrl : null;
        }
        return userInfoData.getObject("data").getObject("card").getString("face").replace("http:", "https:");
    }

    @Override
    public String getBannerUrl() throws ParsingException {
        if (isCardSkipped()) {
            return null;
        }
        return userInfoData.getObject("data").getObject("space").getString("l_img").replace("http:", "https:");
    }

    @Override
    public long getSubscriberCount() throws ParsingException {
        if (isCardSkipped()) {
            return knownChannelCard != null ? knownChannelCard.subscriberCount : -1;
        }
        return userInfoData.getObject("data").getObject("card").getLong("fans");
    }

    @Override
    public String getDescription() throws ParsingException {
        if (isCardSkipped()) {
            return knownChannelCard != null ? knownChannelCard.description : null;
        }
        return userInfoData.getObject("data").getObject("card").getString("sign");
    }

    @Nonnull
    @Override
    public String getUrl() throws ParsingException {
        return super.getUrl();
    }

    @Nonnull
    @Override
    public List<ListLinkHandler> getTabs() throws ParsingException {
        String url = GET_SEASON_ARCHIVES_LIST_BASE_URL + "?mid=" + getLinkHandler().getId() + "&page_num=1&page_size=10";
        return Arrays.asList(
                new ListLinkHandler(getUrl(), url, getLinkHandler().getId(),
                        Collections.singletonList(new FilterItem(Filter.ITEM_IDENTIFIER_UNKNOWN, ChannelTabs.VIDEOS)), null),
                new ListLinkHandler(url, url, getLinkHandler().getId(),
                        Collections.singletonList(new FilterItem(Filter.ITEM_IDENTIFIER_UNKNOWN, ChannelTabs.PLAYLISTS)), null)
        );
    }


    /**
     * Abstract Implementation of extracting User Videos
     */
    public interface UserVideoImpl {

        /**
         * Collect videos from results json
         *
         * @param collector collector to be filled in
         * @param extractor current extractor
         * @param results   Json Array of user videos
         * @throws ParsingException if failed to parse
         */
        void collectVideos(
                StreamInfoItemsCollector collector,
                ChannelExtractor extractor,
                JsonArray results
        ) throws ParsingException;

        void onFetchPage(
                @Nonnull Downloader downloader,
                String id,
                String url
        ) throws IOException, ExtractionException;

        // return true if it contains videos
        boolean getInitialPage(
                @Nonnull StreamInfoItemsCollector collector,
                @Nonnull ChannelExtractor extractor
        ) throws IOException, ExtractionException;

        // return true if it contains videos
        boolean getPage(
                @Nonnull Page page,
                @Nonnull StreamInfoItemsCollector collector,
                @Nonnull Downloader downloader,
                @Nonnull ChannelExtractor extractor,
                @Nonnull String id
        ) throws IOException, ExtractionException;

        /**
         * last video of current page
         *
         * @return av
         */
        long lastVideo();

        /**
         * Name of the uploader as reported by the video list itself, or {@code null} when this
         * mode does not include one.
         */
        default String uploaderName() {
            return null;
        }
    }

    /**
     * Extracting from BiliBili Client API
     */
    public static class ClientUserVideoImpl implements UserVideoImpl {

        public JsonObject userVideoData = new JsonObject();

        private JsonArray getVideosArray() {
            return userVideoData.getObject("data").getArray("item");
        }

        /**
         * The client API answers "is there another page" itself. A non-empty item array is not
         * an answer: a channel with a single video still fills one page, and reporting a next
         * page for it leaves the channel screen with a load-more footer that can never be
         * satisfied - and, because a one item list does not scroll, never even triggers.
         */
        private boolean hasNextPage() {
            try {
                return userVideoData.getObject("data").getBoolean("has_next", false);
            } catch (final RuntimeException e) {
                return false;
            }
        }

        @Override
        public String uploaderName() {
            try {
                final JsonArray items = getVideosArray();
                return items == null || items.isEmpty() ? null : items.getObject(0).getString("author");
            } catch (final RuntimeException e) {
                // No usable list: fall back to downloading the card.
                return null;
            }
        }

        private void fetchViaAPI(Downloader downloader, String id, long lastVideoAid)
                throws ParsingException, IOException, ReCaptchaException {
            Map<String, List<String>> headers = getHeaders(SPACE_REFERER);
            userVideoData = requestUserSpaceResponse(downloader, buildUserVideosUrlClientAPI(id, lastVideoAid), headers);
        }

        static String buildUserVideosUrlClientAPI(String mid, long lastVideoAid) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("vmid", mid);
            if (lastVideoAid > 0) {
                params.put("aid", String.valueOf(lastVideoAid));
            }
            params.put("order", "pubdate");

            DeviceForger.Device device = DeviceForger.requireRandomDevice();
            params.put("mobi_app", APP_TYPE);
            params.put("ts", String.valueOf(System.currentTimeMillis() / 1000));
            params.put("sign", utils.encAppSign(params, APP_KEY, APP_SEC));

            return QUERY_USER_VIDEOS_CLIENT_API_URL + "?" + utils.createQueryString(params);
        }

        @Override
        public void collectVideos(
                StreamInfoItemsCollector collector,
                ChannelExtractor extractor,
                JsonArray results
        ) throws ParsingException {
            for (int i = 0; i < results.size(); i++) {
                collector.commit(new BilibiliChannelInfoItemClientAPIExtractor(results.getObject(i), extractor.getName(), extractor.getAvatarUrl()));
            }
        }

        @Override
        public void onFetchPage(@Nonnull Downloader downloader, String id, String url) throws IOException, ExtractionException {
            fetchViaAPI(downloader, id, 0);
        }

        @Override
        public boolean getInitialPage(
                @Nonnull StreamInfoItemsCollector collector,
                @Nonnull ChannelExtractor extractor
        ) throws IOException, ExtractionException {

            JsonArray videosArray = getVideosArray();

            if (!videosArray.isEmpty()) {
                collectVideos(collector, extractor, videosArray);
            }
            return hasNextPage();
        }

        @Override
        public boolean getPage(
                @Nonnull Page page,
                @Nonnull StreamInfoItemsCollector collector,
                @Nonnull Downloader downloader,
                @Nonnull ChannelExtractor extractor,
                @Nonnull String id
        ) throws IOException, ExtractionException {

            fetchViaAPI(downloader, id, Long.parseLong(page.getId()));

            JsonArray videosArray = getVideosArray();

            if (!videosArray.isEmpty()) {
                collectVideos(collector, extractor, videosArray);
            }
            return hasNextPage();
        }

        @Override
        public long lastVideo() {
            JsonArray videosArray = getVideosArray();
            // Not every entry carries a usable id - a round-play entry, or whatever the space
            // API appends at the end of a page, may have neither param nor bvid. Walk back to
            // the last entry that does rather than failing the whole channel.
            for (int i = videosArray.size() - 1; i >= 0; i--) {
                final JsonObject item = videosArray.getObject(i);
                final String type = item.getString("goto");
                final String num = item.getString("param");
                if ("av".equals(type) && num != null) {
                    try {
                        return Long.parseLong(num);
                    } catch (final NumberFormatException ignored) {
                        // fall through and try the bvid
                    }
                }
                final String bv = item.getString("bvid");
                if (bv != null && !bv.isEmpty()) {
                    return utils.bv2av(bv);
                }
            }
            return 0;
        }
    }

    /**
     * Extracting from BiliBili Search API
     */
    public static class SearchUserVideoImpl implements UserVideoImpl {

        JsonObject userVideoData = new JsonObject();

        private JsonArray getVideosArray() {
            return userVideoData.getObject("data").getArray("archives");
        }

        private void fetchViaAPI(Downloader downloader, String id, String currentUrl)
                throws ParsingException, IOException, ReCaptchaException {
            Map<String, List<String>> headers = getHeaders(currentUrl);
            userVideoData = requestUserSpaceResponse(downloader, buildUserVideosUrlSearchAPI(currentUrl, id), headers);
        }

        static String buildUserVideosUrlSearchAPI(String baseUrl, String id) {

            LinkedHashMap<String, String> params = new LinkedHashMap<>();

            params.put("mid", id);
            params.put("keywords", "");
            params.put("order", "pubdate");

            String pn = "1";
            if (baseUrl.contains("pn=")) {
                pn = baseUrl.split("pn=")[1].split("&")[0];
            }
            params.put("pn", pn);
            params.put("ps", "20");

            params.putAll(utils.getDmImgParams());

            return utils.getWbiResult(QUERY_USER_VIDEOS_SEARCH_API_URL, params);
        }

        @Override
        public void collectVideos(
                StreamInfoItemsCollector collector,
                ChannelExtractor extractor,
                JsonArray results
        ) throws ParsingException {
            for (int i = 0; i < results.size(); i++) {
                collector.commit(new BilibiliChannelInfoItemWebAPIExtractor(results.getObject(i), extractor.getName(), extractor.getAvatarUrl()));
            }
        }

        @Override
        public void onFetchPage(@Nonnull Downloader downloader, String id, String url) throws IOException, ExtractionException {
            fetchViaAPI(downloader, id, url);
        }

        @Override
        public boolean getInitialPage(
                @Nonnull StreamInfoItemsCollector collector,
                @Nonnull ChannelExtractor extractor
        ) throws IOException, ExtractionException {

            JsonArray videos = getVideosArray();

            if (!videos.isEmpty()) {
                collectVideos(collector, extractor, videos);
                return true;
            } else {
                return false;
            }
        }

        @Override
        public boolean getPage(
                @Nonnull Page page,
                @Nonnull StreamInfoItemsCollector collector,
                @Nonnull Downloader downloader,
                @Nonnull ChannelExtractor extractor,
                @Nonnull String id
        ) throws IOException, ExtractionException {

            fetchViaAPI(downloader, id, page.getUrl());

            JsonArray videosArray = getVideosArray();

            if (!videosArray.isEmpty()) {
                collectVideos(collector, extractor, videosArray);
                return true;
            } else {
                return false;
            }
        }

        @Override
        public long lastVideo() {
            JsonArray videos = getVideosArray();
            if (!videos.isEmpty()) {
                JsonObject last = videos.getObject(videos.size() - 1);
                long aid = last.getLong("aid", 0);
                String bvid = last.getString("bvid");
                if (aid > 0) {
                    return aid;
                } else {
                    return utils.bv2av(bvid);
                }
            }
            return 0;
        }
    }

    /**
     * Extracting from BiliBili Web API
     */
    public static class WebUserVideoImpl implements UserVideoImpl {

        JsonObject userVideoData = new JsonObject();

        private JsonArray getVideosArray() {
            return userVideoData.getObject("data").getObject("list").getArray("vlist");
        }

        @Override
        public String uploaderName() {
            try {
                final JsonArray items = getVideosArray();
                return items == null || items.isEmpty() ? null : items.getObject(0).getString("author");
            } catch (final RuntimeException e) {
                // No usable list: fall back to downloading the card.
                return null;
            }
        }

        private void fetchViaAPI(Downloader downloader, String id, String currentUrl)
                throws ParsingException, IOException, ReCaptchaException {
            Map<String, List<String>> headers = getHeaders(currentUrl);
            userVideoData = requestUserSpaceResponse(downloader, buildUserVideosUrlWebAPI(currentUrl, id), headers);
        }


        static String buildUserVideosUrlWebAPI(String baseUrl, String id) {
            LinkedHashMap<String, String> params = new LinkedHashMap<>();

            params.put("mid", id);
            params.put("order", "pubdate");
            params.put("ps", "25");

            String pn = "1";
            if (baseUrl.contains("pn=")) {
                pn = baseUrl.split("pn=")[1].split("&")[0];
            }
            params.put("pn", pn);

            params.put("order_avoided", "true");

            params.put("platform", "web");
            params.put("web_location", "333.1387");

            params.putAll(utils.getDmImgParams());

            // no longer needed currently
            // params.put("w_webid", webIdCache.get(id));

            return utils.getWbiResult(QUERY_USER_VIDEOS_WEB_API_URL, params);
        }

        @Override
        public void collectVideos(
                StreamInfoItemsCollector collector,
                ChannelExtractor extractor,
                JsonArray results
        ) throws ParsingException {
            for (int i = 0; i < results.size(); i++) {
                collector.commit(new BilibiliChannelInfoItemWebAPIExtractor(results.getObject(i), null, null));
            }
        }

        @Override
        public void onFetchPage(@Nonnull Downloader downloader, String id, String url) throws IOException, ExtractionException {
            fetchViaAPI(downloader, id, url);
        }

        @Override
        public boolean getInitialPage(
                @Nonnull StreamInfoItemsCollector collector,
                @Nonnull ChannelExtractor extractor
        ) throws IOException, ExtractionException {

            JsonArray videos = getVideosArray();

            if (!videos.isEmpty()) {
                collectVideos(collector, extractor, videos);
                return true;
            } else {
                return false;
            }
        }

        @Override
        public boolean getPage(
                @Nonnull Page page,
                @Nonnull StreamInfoItemsCollector collector,
                @Nonnull Downloader downloader,
                @Nonnull ChannelExtractor extractor,
                @Nonnull String id
        ) throws IOException, ExtractionException {

            fetchViaAPI(downloader, id, page.getUrl());

            JsonArray videosArray = getVideosArray();

            if (!videosArray.isEmpty()) {
                collectVideos(collector, extractor, videosArray);
                return true;
            } else {
                return false;
            }
        }

        @Override
        public long lastVideo() {
            JsonArray videos = getVideosArray();
            if (!videos.isEmpty()) {
                JsonObject last = videos.getObject(videos.size() - 1);
                long aid = last.getLong("aid", 0);
                String bvid = last.getString("bvid");
                if (aid > 0) {
                    return aid;
                } else {
                    return utils.bv2av(bvid);
                }
            }
            return 0;
        }
    }


    public static JsonObject requestUserSpaceResponse(
            Downloader downloader,
            String url,
            Map<String, List<String>> headers
    ) throws ParsingException, IOException, ReCaptchaException {
        int maxTry = 2;
        int currentTry = maxTry;

        String responseBody = "";
        // The url of the risk control page, when that is what came back, so the caller can be
        // sent to it instead of only being told that something was refused.
        String riskControlPage = null;

        while (currentTry > 0) {
            // Risk control pauses everything for a while; only continue once that has passed.
            BilibiliService.awaitCooldown();
            // Rebuild the cookies, otherwise the retry carries the identity that was refused.
            headers.put("Cookie",
                    Collections.singletonList(BilibiliService.getCookieHeader()));
            responseBody = downloader.get(url, headers).responseBody();
            if (responseBody.startsWith("<!DOCTYPE html>")) {
                // returns HTML, due to risk control
                riskControlPage = url;
                BilibiliService.onBlocked();
                currentTry -= 1;
                continue;
            }
            try {
                JsonObject responseJson = JsonParser.object().from(responseBody);
                long code = responseJson.getLong("code");
                if (code != 0) {
                    if (code == -352) {
                        // blocked risk control
                        BilibiliService.onBlocked();
                    }
                    currentTry -= 1;
                } else {
                    BilibiliService.onSuccess();
                    return responseJson;
                }
            } catch (JsonParserException e) {
                e.printStackTrace();
                throw new ParsingException("Failed parse response body: " + responseBody);
            }
        }

        DeviceForger.Device device = DeviceForger.requireRandomDevice();
        String msg = "BiliBili blocked us, we retried " + maxTry + " times, the last forged device is:\n"
                + device.info()
                + "\nTry to refresh, or report this!\n"
                + responseBody;
        rotateVideoApiMode();
        BilibiliService.onBlocked(); // keep the pause in place, the retry already failed

        if (riskControlPage != null) {
            // PipePipe knows what to do with this one: the error panel turns it into a button
            // that opens the url in a WebView, where BiliBili's own page - the very one that was
            // returned here - solves the challenge and leaves its cookies behind for the retry.
            throw new ReCaptchaException(
                    "BiliBili risk control refused the request", riskControlPage);
        }
        throw new ParsingException(msg);
    }

}
