package com.example.xyzreader.ui;

import android.annotation.SuppressLint;
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
    private boolean mPendingTransition = false;
    private int mReturnPos = 0;

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
                    //are we coming back from a detail view?
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

    private static int mPosition;           //used to indicate a required scroll position...
    @Override
    public void onActivityReenter(int requestCode, Intent data) {
        super.onActivityReenter(requestCode, data);
        //Get bundle from detail activity (pass back parameters on what the currently selected story might be)
        mReenterStateBundle = new Bundle(data.getExtras());
        int startingPos = mReenterStateBundle.getInt(EXTRA_STARTING_STORY_POS);
        int currentPos = mReenterStateBundle.getInt(EXTRA_CURRENT_STORY_POS);
        mReturnPos = currentPos;    //save off the return position
        mPosition = -1;
        if (startingPos != currentPos) {
            //scroll to currentId - note, convert the ID to position...
            mPosition = currentPos;
            //scroll here (need to get the element visible for transitions to work)
            mRecyclerView.scrollToPosition(mPosition);
        }

        //Hold off on any animation until we are ready to redraw view.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            postponeEnterTransition();


            //Check if this is cardview...
            if (getResources().getBoolean(R.bool.detail_is_card)) {
                //To fix tablet...
                //Google appears to have optimized draw code if you have a transparent window on top of
                //a view (they never call onPreDraw). Invalidate view to force below onPreDraw to trigger.
                mRecyclerView.invalidate();
            }

            mRecyclerView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @SuppressLint("NewApi")
                @Override
                public boolean onPreDraw() {
                    mRecyclerView.getViewTreeObserver().removeOnPreDrawListener(this);
                    // Figure out why it is necessary to request layout here in order to get a smooth transition. (Per Alex Lockwood)
                    // Aha - figure it out... If activity is killed due to rotation, there are no image views
                    // with a transition name to bind to (not until bind is called from the recycler adapter).
                    // So, the transition which was set up is cleared since there is no corresponding image element
                    // that has the required transition name in the recycler view.
                    // Or, to put it simply, you have to bind the recycler views to populate the transition names
                    // before you can let the transition go through. In the case of the album example,
                    // requestLayout performed this forcing function. In the case of an adapter, it does not.
                    // So, how to fix? Well, if mAdapter == null, we haven't yet bound. So set a pending
                    // request for entering transition using a private bool. And take care of it at
                    // the end of the loader callback...
                    //
                    // And one bug here. If no adapter, no scrolling. And if view on on page, can't transition.
                    if (mAdapter != null) {
                        mRecyclerView.requestLayout();
                        if (mPosition >= 0) {
                            //And it turns out you need to scroll again to proper position after requesting layout.
                            mRecyclerView.scrollToPosition(mPosition);
                            mPosition = -1;
                        }

                        //ready to start the transition
                        startPostponedEnterTransition();
                    } else {
                        //Ugg - no views bound yet. Postpone to after the views are bound.
                        //This turned out to be the most difficult portion of the exercise. No good listener event
                        //for when views are bound but before transitions are validated by android.
                        //Do it on onViewAttachedToWindow callback. Set a semiphore trigger.
                        mPendingTransition = true;  //set a flag to do this at end of loader...

                        //okay - this gets tricky. If view not on screen, we will never get called in
                        //onViewAttachedToWindow and transition gets stuck...
                        //so, to fix, if mPosition not null, kick off thread to scroll to position
                        if (mPosition >= 0) {
                            //kick off UI thread for scroll.
                            mRecyclerView.post(new Runnable() {
                                @Override
                                public void run() {
                                    //And then do the scroll
                                    mRecyclerView.scrollToPosition(mPosition);
                                    mPosition = -1;
                                }
                            });
                        }

                    }

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
        public void onViewAttachedToWindow(ViewHolder view) {
            //Views are bound at this point. Can call transition code here.
            //This is for the case when activity killed in detail screen (i.e. rotation) and views not
            //bound in activity reenter. We have to delay transition to when the view is bound. (this callback).
            //Note - don't worry about pre-SDK21 - mPendingTransition only set for L or higher...
            if (mPendingTransition) {
                if (mReturnPos == view.getAdapterPosition()) {
                    mPendingTransition = false;     //clear out any pending transition
                    //okay, we have bound the view we need for the transition
                    startPostponedEnterTransition();
                }
            }
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
