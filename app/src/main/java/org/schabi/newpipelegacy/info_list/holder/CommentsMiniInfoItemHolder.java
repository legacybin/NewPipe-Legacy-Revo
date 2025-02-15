package org.schabi.newpipelegacy.info_list.holder;

import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.text.util.LinkifyCompat;

import org.schabi.newpipelegacy.R;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipelegacy.info_list.InfoItemBuilder;
import org.schabi.newpipelegacy.local.history.HistoryRecordManager;
import org.schabi.newpipelegacy.report.ErrorActivity;
import org.schabi.newpipelegacy.util.AndroidTvUtils;
import org.schabi.newpipelegacy.util.CommentTextOnTouchListener;
import org.schabi.newpipelegacy.util.ImageDisplayConstants;
import org.schabi.newpipelegacy.util.Localization;
import org.schabi.newpipelegacy.util.NavigationHelper;
import org.schabi.newpipelegacy.util.ShareUtils;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.hdodenhof.circleimageview.CircleImageView;

public class CommentsMiniInfoItemHolder extends InfoItemHolder {
    private static final int COMMENT_DEFAULT_LINES = 2;
    private static final int COMMENT_EXPANDED_LINES = 1000;
    private static final Pattern PATTERN = Pattern.compile("(\\d+:)?(\\d+)?:(\\d+)");

    public final CircleImageView itemThumbnailView;
    private final TextView itemContentView;
    private final TextView itemLikesCountView;
    private final TextView itemDislikesCountView;
    private final TextView itemPublishedTime;

    private String commentText;
    private String streamUrl;

    private final Linkify.TransformFilter timestampLink = new Linkify.TransformFilter() {
        @Override
        public String transformUrl(final Matcher match, final String url) {
            int timestamp = 0;
            String hours = match.group(1);
            String minutes = match.group(2);
            String seconds = match.group(3);
            if (hours != null) {
                timestamp += (Integer.parseInt(hours.replace(":", "")) * 3600);
            }
            if (minutes != null) {
                timestamp += (Integer.parseInt(minutes.replace(":", "")) * 60);
            }
            if (seconds != null) {
                timestamp += (Integer.parseInt(seconds));
            }
            return streamUrl + url.replace(match.group(0), "#timestamp=" + timestamp);
        }
    };

    CommentsMiniInfoItemHolder(final InfoItemBuilder infoItemBuilder, final int layoutId,
                               final ViewGroup parent) {
        super(infoItemBuilder, layoutId, parent);

        itemThumbnailView = itemView.findViewById(R.id.itemThumbnailView);
        itemLikesCountView = itemView.findViewById(R.id.detail_thumbs_up_count_view);
        itemDislikesCountView = itemView.findViewById(R.id.detail_thumbs_down_count_view);
        itemPublishedTime = itemView.findViewById(R.id.itemPublishedTime);
        itemContentView = itemView.findViewById(R.id.itemCommentContentView);
    }

    public CommentsMiniInfoItemHolder(final InfoItemBuilder infoItemBuilder,
                                      final ViewGroup parent) {
        this(infoItemBuilder, R.layout.list_comments_mini_item, parent);
    }

    @Override
    public void updateFromItem(final InfoItem infoItem,
                               final HistoryRecordManager historyRecordManager) {
        if (!(infoItem instanceof CommentsInfoItem)) {
            return;
        }
        final CommentsInfoItem item = (CommentsInfoItem) infoItem;

        itemBuilder.getImageLoader()
                .displayImage(item.getUploaderAvatarUrl(),
                        itemThumbnailView,
                        ImageDisplayConstants.DISPLAY_THUMBNAIL_OPTIONS);

        itemThumbnailView.setOnClickListener(view -> openCommentAuthor(item));

        streamUrl = item.getUrl();

        itemContentView.setLines(COMMENT_DEFAULT_LINES);
        try {
            commentText = item.getCommentText().getContent().replace("&nbsp;", "")
                    .replace("<a href=", "").replace("</a>", " ")
                    .replace("<br>", " ").replace("<b>", " ")
                    .replace("</br>", " ").replace("</b>", " ");
        } catch (Exception e) {
            commentText = " ";
        }
        itemContentView.setText(commentText);
        itemContentView.setOnTouchListener(CommentTextOnTouchListener.INSTANCE);

        if (itemContentView.getLineCount() == 0) {
            itemContentView.post(this::ellipsize);
        } else {
            ellipsize();
        }

        if (item.getLikeCount() >= 0) {
            itemLikesCountView.setText(String.valueOf(item.getLikeCount()));
        } else {
            itemLikesCountView.setText("-");
        }

        if (item.getUploadDate() != null) {
            itemPublishedTime.setText(Localization.relativeTime(item.getUploadDate().date()));
        } else {
            itemPublishedTime.setText(item.getTextualUploadDate());
        }

        itemView.setOnClickListener(view -> {
            toggleEllipsize();
            if (itemBuilder.getOnCommentsSelectedListener() != null) {
                itemBuilder.getOnCommentsSelectedListener().selected(item);
            }
        });


        itemView.setOnLongClickListener(view -> {
            if (AndroidTvUtils.isTv(itemBuilder.getContext())) {
                openCommentAuthor(item);
            } else {
                ShareUtils.copyToClipboard(itemBuilder.getContext(), commentText);
            }
            return true;
        });
    }

    private void openCommentAuthor(final CommentsInfoItem item) {
        if (TextUtils.isEmpty(item.getUploaderUrl())) {
            return;
        }
        try {
            final AppCompatActivity activity = (AppCompatActivity) itemBuilder.getContext();
            NavigationHelper.openChannelFragment(
                    activity.getSupportFragmentManager(),
                    item.getServiceId(),
                    item.getUploaderUrl(),
                    item.getUploaderName());
        } catch (Exception e) {
            ErrorActivity.reportUiError((AppCompatActivity) itemBuilder.getContext(), e);
        }
    }

    private void allowLinkFocus() {
        itemContentView.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private void denyLinkFocus() {
        itemContentView.setMovementMethod(null);
    }

    private boolean shouldFocusLinks() {
        if (itemView.isInTouchMode()) {
            return false;
        }

        URLSpan[] urls = itemContentView.getUrls();

        return urls != null && urls.length != 0;
    }

    private void determineLinkFocus() {
        if (shouldFocusLinks()) {
            allowLinkFocus();
        } else {
            denyLinkFocus();
        }
    }

    private void ellipsize() {
        boolean hasEllipsis = false;

        if (itemContentView.getLineCount() > COMMENT_DEFAULT_LINES) {
            int endOfLastLine = itemContentView.getLayout().getLineEnd(COMMENT_DEFAULT_LINES - 1);
            int end = itemContentView.getText().toString().lastIndexOf(' ', endOfLastLine - 2);
            if (end == -1) {
                end = Math.max(endOfLastLine - 2, 0);
            }
            String newVal = itemContentView.getText().subSequence(0, end) + " …";
            itemContentView.setText(newVal);
            hasEllipsis = true;
        }

        linkify();

        if (hasEllipsis) {
            denyLinkFocus();
        } else {
            determineLinkFocus();
        }
    }

    private void toggleEllipsize() {
        if (itemContentView.getText().toString().equals(commentText)) {
            if (itemContentView.getLineCount() > COMMENT_DEFAULT_LINES) {
                ellipsize();
            }
        } else {
            expand();
        }
    }

    private void expand() {
        itemContentView.setMaxLines(COMMENT_EXPANDED_LINES);
        itemContentView.setText(commentText);
        linkify();
        determineLinkFocus();
    }

    private void linkify() {
        Linkify.addLinks(itemContentView, Linkify.WEB_URLS);
        Linkify.addLinks(itemContentView, PATTERN, null, null, timestampLink);
    }
}
