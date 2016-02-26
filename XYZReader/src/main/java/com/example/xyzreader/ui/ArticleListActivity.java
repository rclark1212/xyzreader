package com.example.xyzreader.ui;

import android.annotation.TargetApi;
import android.app.ActivityOptions;
import android.app.LoaderManager;
import android.app.SharedElementCallback;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Loader;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.support.design.widget.AppBarLayout;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.text.format.DateUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import com.example.xyzreader.R;
import com.example.xyzreader.data.ArticleLoader;
import com.example.xyzreader.data.ItemsContract;
import com.example.xyzreader.data.UpdaterService;

import java.util.List;
import java.util.Map;

/**
 * An activity representing a list of Articles. This activity has different presentations for
 * handset and tablet-size devices. On handsets, the activity presents a list of items, which when
 * touched, lead to a {@link ArticleDetailActivity} representing item details. On tablets, the
 * activity presents a grid of items as cards.
 */
public class ArticleListActivity extends ActionBarActivity implements
        LoaderManager.LoaderCallbacks<Cursor> {

    private AppBarLayout mToolbar;
    private SwipeRefreshLayout mSwipeRefreshLayout;
    private RecyclerView mRecyclerView;
    private Bundle mReenterStateBundle = null;
    private boolean mIsDetailsActivityStarted = false;
    private boolean mIsRefreshing = false;
    private Adapter mAdapter;

    private static boolean mbInternet = true;    //set to false to disable transitions

    static final String EXTRA_STARTING_STORY_POS = "extra_starting_story_pos";
    static final String EXTRA_CURRENT_STORY_POS = "extra_current_story_pos";
    static final String EXTRA_VIEW_LIST_POSITION = "extra_view_position";
    /*
        Transition code based on Alex Lockwood's excellent activity-transitions sample on github
     */

    //Set a callback for shared element transition
    private SharedElementCallback mCallback = null;
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {

            mCallback = new SharedElementCallback() {
                @TargetApi(Build.VERSION_CODES.LOLLIPOP)
                @Override
                public void onMapSharedElements(List<String> names, Map<String, View> sharedElements) {
                    if (mReenterStateBundle != null) {
                        //Check here if we are coming back to a different story than when we started. And fix up.
                        int startingPos = mReenterStateBundle.getInt(EXTRA_STARTING_STORY_POS);
                        int currentPos = mReenterStateBundle.getInt(EXTRA_CURRENT_STORY_POS);
                        if (startingPos != currentPos) {
                            // If startingPosition != currentPosition the user must have swiped to a
                            // different page in the DetailsActivity. We must update the shared element
                            // so that the correct one falls into place.
                            String newTransitionName = getResources().getString(R.string.transition) + currentPos;
                            View newSharedElement = mRecyclerView.findViewWithTag(currentPos);
                            if (newSharedElement != null) {
                                names.clear();
                                names.add(newTransitionName);
                                sharedElements.clear();
                                sharedElements.put(newTransitionName, newSharedElement);
                            }
                        }

                        mReenterStateBundle = null;
                    } else {
                        // If mReenterStateBundle is null, then the activity is exiting.
                        View navigationBar = findViewById(android.R.id.navigationBarBackground);
                        View statusBar = findViewById(android.R.id.statusBarBackground);
                        if (navigationBar != null) {
                            names.add(navigationBar.getTransitionName());
                            sharedElements.put(navigationBar.getTransitionName(), navigationBar);
                        }
                        if (statusBar != null) {
                            names.add(statusBar.getTransitionName());
                            sharedElements.put(statusBar.getTransitionName(), statusBar);
                        }
                    }
                }
            };
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_article_list);

        //Set a shared element callback for the case where we may be exiting to a different element than we start from
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setExitSharedElementCallback(mCallback);
        }

        //Change toolbar global to the app bar (used later)
        mToolbar = (AppBarLayout) findViewById(R.id.app_bar_layout);


        //unused view (and move to coordinatorlayout)
        //final View toolbarContainerView = findViewById(R.id.toolbar_container);
        //Get internet state (used for shared element transition if no internet - see articledetailactivity)
        mbInternet = isOnline();

        mSwipeRefreshLayout = (SwipeRefreshLayout) findViewById(R.id.swipe_refresh_layout);

        //add listener for the refresh... (fixes the refresh bug in the original code)
        mSwipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                refresh();
            }
        });

        mRecyclerView = (RecyclerView) findViewById(R.id.recycler_view);
        getLoaderManager().initLoader(0, null, this);

        if (savedInstanceState == null) {
            refresh();
        }
    }

    private void refresh() {
        startService(new Intent(this, UpdaterService.class));
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerReceiver(mRefreshingReceiver,
                new IntentFilter(UpdaterService.BROADCAST_ACTION_STATE_CHANGE));
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(mRefreshingReceiver);
    }

    @Override
    protected void onResume() {
        super.onResume();
        //on resume, we know details activity not active
        mIsDetailsActivityStarted = false;
    }

    private static int mPosition;
    @Override
    public void onActivityReenter(int requestCode, Intent data) {
        super.onActivityReenter(requestCode, data);
        //Get bundle from detail activity (pass back parameters on what the currently selected story might be)
        mReenterStateBundle = new Bundle(data.getExtras());
        int startingPos = mReenterStateBundle.getInt(EXTRA_STARTING_STORY_POS);
        int currentPos = mReenterStateBundle.getInt(EXTRA_CURRENT_STORY_POS);
        mPosition = -1;
        if (startingPos != currentPos) {
            //scroll to currentId - note, convert the ID to position...
            mPosition = currentPos;
            //scroll here (need to get the element visible for transitions to work)
            mRecyclerView.scrollToPosition(mPosition);
        }

        //Hold off on any animation until we are ready to redraw view. Looks like a bug that Alex Lockwood worked around
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            postponeEnterTransition();

            //FIXME - if you go to detail screen on tablet, then sleep/wake, then go back...
            //you have a timing issue. This code gets executed but the onPreDraw never fires. =corruption.
            //With nexus6 works fine. With n9 and not going to sleep, works fine. Only n9 and sleep...
            //Denver???
            //Test1 - disable all special styles - works...
            //Test2 - disable dim/null cache hint - fails...
            //Test3 - reversed test2. re-enable all, disable windowtranslucebt/transluctent background - works...
            //Test4 - only disable windowtranslucent. works.
            //Test5 - reverse above. Enable all but translucent background. Fails. We have a winner...
            //android:windowIsTranslucent">true - now search for bugs on internet...
            //verify that shared elements need to be enabled...
            mRecyclerView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    mRecyclerView.getViewTreeObserver().removeOnPreDrawListener(this);
                    // TODO: figure out why it is necessary to request layout here in order to get a smooth transition. (Per Alex Lockwood)
                    mRecyclerView.requestLayout();
                    if (mPosition >= 0) {
                        //And it turns out you need to scroll again to proper position after requesting layout.
                        mRecyclerView.scrollToPosition(mPosition);
                    }
                    startPostponedEnterTransition();
                    return true;
                }
            });
        } else if (mPosition >= 0) {
            //For pre-L devices
            mRecyclerView.scrollToPosition(mPosition);
        }
    }

    private BroadcastReceiver mRefreshingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (UpdaterService.BROADCAST_ACTION_STATE_CHANGE.equals(intent.getAction())) {
                mIsRefreshing = intent.getBooleanExtra(UpdaterService.EXTRA_REFRESHING, false);
                updateRefreshingUI();
            }
        }
    };

    private void updateRefreshingUI() {
        mSwipeRefreshLayout.setRefreshing(mIsRefreshing);
    }

    //
    //  Utility routine to check if we have internet connection. Check on start
    //
    public boolean isOnline() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();

        if (netInfo == null)
            return false;

        return netInfo.isConnected();
    }


    @Override
    public Loader<Cursor> onCreateLoader(int i, Bundle bundle) {
        return ArticleLoader.newAllArticlesInstance(this);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> cursorLoader, Cursor cursor) {
        mAdapter = new Adapter(cursor);
        mAdapter.setHasStableIds(true);
        mRecyclerView.setAdapter(mAdapter);
        int columnCount = getResources().getInteger(R.integer.list_column_count);
        StaggeredGridLayoutManager sglm =
                new StaggeredGridLayoutManager(columnCount, StaggeredGridLayoutManager.VERTICAL);
        mRecyclerView.setLayoutManager(sglm);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mRecyclerView.setAdapter(null);
    }

    private class Adapter extends RecyclerView.Adapter<ViewHolder> {
        private Cursor mCursor;

        public Adapter(Cursor cursor) {
            mCursor = cursor;
        }

        @Override
        public long getItemId(int position) {
            mCursor.moveToPosition(position);
            return mCursor.getLong(ArticleLoader.Query._ID);
        }


        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.list_item_article, parent, false);
            final ViewHolder vh = new ViewHolder(view);
            view.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    //prevent double clicking
                    if (!mIsDetailsActivityStarted) {
                        mIsDetailsActivityStarted = true;

                        if (!mbInternet) {
                            //Get internet state (used for shared element transition if no internet - see articledetailactivity)
                            //check it again here in case internet came back... (could do with a broadcast receiver but
                            //also not high overhead to poll when user selects a story)...
                            mbInternet = isOnline();
                        }

                        //set up the base intent
                        Intent intent = new Intent(Intent.ACTION_VIEW, ItemsContract.Items.buildItemUri(getItemId(vh.getAdapterPosition())));

                        //add the position here as well...
                        intent.putExtra(EXTRA_VIEW_LIST_POSITION, vh.getAdapterPosition());

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            //late binding setting of transition name
                            vh.thumbnailView.setTransitionName(getString(R.string.transition) + vh.getAdapterPosition());
                            ActivityOptions options = ActivityOptions.makeSceneTransitionAnimation(ArticleListActivity.this, vh.thumbnailView,
                                    vh.thumbnailView.getTransitionName());

                            startActivity(intent, options.toBundle());
                        } else {
                            startActivity(intent);
                        }
                    }
                }
            });
            return vh;
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            mCursor.moveToPosition(position);
            holder.titleView.setText(mCursor.getString(ArticleLoader.Query.TITLE));
            //fix string formatting (for localization)
            holder.subtitleView.setText(String.format(getString(R.string.byline_format),
                    DateUtils.getRelativeTimeSpanString(
                            mCursor.getLong(ArticleLoader.Query.PUBLISHED_DATE),
                            System.currentTimeMillis(), DateUtils.HOUR_IN_MILLIS,
                            DateUtils.FORMAT_ABBREV_ALL).toString(),
                    mCursor.getString(ArticleLoader.Query.AUTHOR)));
            holder.thumbnailView.setImageUrl(
                    mCursor.getString(ArticleLoader.Query.THUMB_URL),
                    ImageLoaderHelper.getInstance(ArticleListActivity.this).getImageLoader());
            holder.thumbnailView.setAspectRatio(mCursor.getFloat(ArticleLoader.Query.ASPECT_RATIO));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                holder.thumbnailView.setTransitionName(getString(R.string.transition) + position); //set transition name (unique per story)
            }
            holder.thumbnailView.setTag(position);                          //record the ID this thumbnail represents
        }

        @Override
        public int getItemCount() {
            return mCursor.getCount();
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public DynamicHeightNetworkImageView thumbnailView;
        public TextView titleView;
        public TextView subtitleView;

        public ViewHolder(View view) {
            super(view);
            thumbnailView = (DynamicHeightNetworkImageView) view.findViewById(R.id.thumbnail);
            //set error image here (in case of no network for example)...
            //and note that older (SDK16 at least) versions of android do not support vector gfx.
            if ((!mbInternet) && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)) {
                thumbnailView.setErrorImageResId(R.drawable.ic_sync_problem_black);
            }
            titleView = (TextView) view.findViewById(R.id.article_title);
            subtitleView = (TextView) view.findViewById(R.id.article_subtitle);
        }
    }
}
