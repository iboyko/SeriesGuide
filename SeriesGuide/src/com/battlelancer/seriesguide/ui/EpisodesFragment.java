
package com.battlelancer.seriesguide.ui;

import com.battlelancer.seriesguide.Constants;
import com.battlelancer.seriesguide.R;
import com.battlelancer.seriesguide.WatchedBox;
import com.battlelancer.seriesguide.provider.SeriesContract.Episodes;
import com.battlelancer.seriesguide.provider.SeriesContract.Seasons;
import com.battlelancer.seriesguide.provider.SeriesContract.Shows;
import com.battlelancer.seriesguide.provider.SeriesGuideDatabase.Tables;
import com.battlelancer.seriesguide.util.AnalyticsUtils;
import com.battlelancer.seriesguide.util.DBUtils;
import com.battlelancer.seriesguide.util.Utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.app.ListFragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.view.Menu;
import android.support.v4.view.MenuItem;
import android.support.v4.widget.SimpleCursorAdapter;
import android.support.v4.widget.SimpleCursorAdapter.ViewBinder;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.MenuInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ListView;
import android.widget.TextView;

public class EpisodesFragment extends ListFragment implements LoaderManager.LoaderCallbacks<Cursor> {

    private static final int MARK_WATCHED_ID = 0;

    private static final int MARK_UNWATCHED_ID = 1;

    private static final int EPISODES_LOADER = 4;

    private static final int DELETE_EPISODE_ID = 2;

    private Constants.EpisodeSorting sorting;

    private boolean mDualPane;

    private SimpleCursorAdapter mAdapter;

    public void fireTrackerEvent(String label) {
        AnalyticsUtils.getInstance(getActivity()).trackEvent("Episodes", "Click", label, 0);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AnalyticsUtils.getInstance(getActivity()).trackPageView("/Episodes");
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        getListView().setSelector(R.drawable.list_selector_holo_dark);

        updatePreferences();

        // Check to see if we have a frame in which to embed the details
        // fragment directly in the containing UI.
        View detailsFragment = getActivity().findViewById(R.id.fragment_details);
        mDualPane = detailsFragment != null && detailsFragment.getVisibility() == View.VISIBLE;

        if (mDualPane) {
            getListView().setChoiceMode(ListView.CHOICE_MODE_SINGLE);
            loadFirstEpisode();
        }

        String[] from = new String[] {
                Episodes.WATCHED, Episodes.TITLE, Episodes.NUMBER, Episodes.FIRSTAIRED
        };
        int[] to = new int[] {
                R.id.CustomCheckBoxWatched, R.id.TextViewEpisodeListTitle,
                R.id.TextViewEpisodeListNumber, R.id.TextViewEpisodeListAirdate
        };

        mAdapter = new SimpleCursorAdapter(getActivity(), R.layout.episode_row, null, from, to, 0);
        mAdapter.setViewBinder(new ViewBinder() {

            public boolean setViewValue(View view, final Cursor cursor, int columnIndex) {
                // binding the watched column? set checkbox to watched value if
                // yes
                if (columnIndex == EpisodesQuery.WATCHED) {
                    WatchedBox wb = (WatchedBox) view;

                    // save rowid to hand over to OnClick event listener
                    final String rowid = cursor.getString(EpisodesQuery._ID);

                    wb.setOnClickListener(new OnClickListener() {

                        public void onClick(View v) {
                            ((WatchedBox) v).toggle();
                            markEpisode(rowid, ((WatchedBox) v).isChecked());
                        }
                    });

                    wb.setChecked(cursor.getInt(columnIndex) > 0);

                    return true;
                } else if (columnIndex == EpisodesQuery.NUMBER) {
                    // set episode number and if available dvd episode number
                    TextView tv = (TextView) view;
                    String episodenumber = getString(R.string.episode) + " "
                            + cursor.getString(EpisodesQuery.NUMBER);
                    float dvdnumber = cursor.getFloat(EpisodesQuery.DVDNUMBER);
                    if (dvdnumber != 0.0) {
                        episodenumber += " (" + dvdnumber + ")";
                    }
                    tv.setText(episodenumber);
                    return true;
                } else if (columnIndex == EpisodesQuery.FIRSTAIRED) {
                    TextView tv = (TextView) view;
                    String fieldValue = cursor.getString(EpisodesQuery.FIRSTAIRED);
                    if (fieldValue.length() != 0) {
                        tv.setText(Utils.parseDateToLocalRelative(fieldValue,
                                cursor.getLong(EpisodesQuery.SHOW_AIRSTIME), getActivity()));
                    } else {
                        tv.setText(getString(R.string.episode_firstaired) + " "
                                + getString(R.string.episode_unkownairdate));
                    }
                    return true;
                }
                // if we did not bind, let the cursor adapter try text and image
                // views
                return false;
            }
        });
        setListAdapter(mAdapter);

        getLoaderManager().initLoader(EPISODES_LOADER, null, this);

        registerForContextMenu(getListView());
        setHasOptionsMenu(true);
    }

    /**
     * Load the first episode manually while the CursorLoader is still getting
     * its data.
     */
    private void loadFirstEpisode() {
        final Activity context = getActivity();
        if (context != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    // Make sure no fragment is already shown
                    EpisodeDetailsFragment detailsFragment = (EpisodeDetailsFragment) getSupportFragmentManager()
                            .findFragmentById(R.id.fragment_details);
                    if (detailsFragment == null) {
                        if (context != null) {
                            // get episodes
                            final Cursor episodes = context.getContentResolver().query(
                                    Episodes.buildEpisodesOfSeasonWithShowUri(getSeasonId()),
                                    new String[] {
                                        Episodes._ID
                                    }, null, null, sorting.query());
                            // show the first one, if there are any
                            if (episodes.getCount() > 0) {
                                episodes.moveToFirst();
                                final String episodeId = episodes.getString(0);
                                context.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        showDetails(episodeId);
                                    }
                                });
                            }
                            episodes.close();
                        }
                    }
                }
            }).start();
        }
    }

    /**
     * Convenience method for showDetails(episodeId) which looks up the episode
     * id in the list view at the given position.
     * 
     * @param position
     */
    private void showDetails(int position) {
        String episodeId = String.valueOf(getListView().getItemIdAtPosition(position));
        getListView().setItemChecked(position, true);
        showDetails(episodeId);
    }

    /**
     * If not already shown, display a new fragment containing the given
     * episodes information.
     * 
     * @param episodeId
     */
    private void showDetails(String episodeId) {
        if (mDualPane) {
            // Check if fragment is shown, create new if needed.
            EpisodeDetailsFragment detailsFragment = (EpisodeDetailsFragment) getFragmentManager()
                    .findFragmentById(R.id.fragment_details);
            if (detailsFragment == null
                    || !detailsFragment.getEpisodeId().equalsIgnoreCase(episodeId)) {
                // Make new fragment to show this selection.
                detailsFragment = EpisodeDetailsFragment.newInstance(episodeId, true);

                // Execute a transaction, replacing any existing
                // fragment with this one inside the frame.
                FragmentTransaction ft = getFragmentManager().beginTransaction();
                ft.replace(R.id.fragment_details, detailsFragment, "fragmentDetails");
                ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
                ft.commit();
            }

        } else {
            Intent intent = new Intent();
            intent.setClass(getActivity(), EpisodeDetailsActivity.class);
            intent.putExtra(BaseColumns._ID, episodeId);
            startActivity(intent);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updatePreferences();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        // only display the action appropiate for the items current state
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        WatchedBox watchedBox = (WatchedBox) info.targetView
                .findViewById(R.id.CustomCheckBoxWatched);
        if (watchedBox.isChecked()) {
            menu.add(0, MARK_UNWATCHED_ID, 1, R.string.unmark_episode);
        } else {
            menu.add(0, MARK_WATCHED_ID, 0, R.string.mark_episode);
        }
        menu.add(0, DELETE_EPISODE_ID, 2, R.string.delete_show);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();

        switch (item.getItemId()) {
            case MARK_WATCHED_ID:
                markEpisode(String.valueOf(info.id), true);
                return true;
            case MARK_UNWATCHED_ID:
                markEpisode(String.valueOf(info.id), false);
                return true;
            case DELETE_EPISODE_ID:
                getActivity().getContentResolver().delete(
                        Episodes.buildEpisodeUri(String.valueOf(info.id)), null, null);
                getActivity().getContentResolver().notifyChange(
                        Episodes.buildEpisodesOfSeasonWithShowUri(getSeasonId()), null);
                return true;
        }
        return super.onContextItemSelected(item);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.episodelist_menu, menu);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            final CharSequence[] items = getResources().getStringArray(R.array.epsorting);
            menu.findItem(R.id.menu_epsorting).setTitle(
                    getString(R.string.sort) + ": " + items[sorting.index()]);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.mark_all:
                fireTrackerEvent("Mark all episodes");

                markAllEpisodes(true);
                return true;
            case R.id.unmark_all:
                fireTrackerEvent("Unmark all episodes");

                markAllEpisodes(false);
                return true;
            case R.id.menu_epsorting:
                fireTrackerEvent("Sort episodes");

                // Create and show the dialog.
                SortDialogFragment newFragment = SortDialogFragment.newInstance(sorting.index(),
                        R.array.epsorting);
                FragmentTransaction ft = getFragmentManager().beginTransaction();
                newFragment.show(ft, "sortEpisodesDialog");
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        showDetails(position);
    }

    public String getSeasonId() {
        return getArguments().getString(Seasons._ID);
    }

    private void markEpisode(final String episodeId, final boolean state) {
        final Activity activity = getActivity();
        if (activity != null) {
            new Thread(new Runnable() {
                public void run() {
                    DBUtils.markEpisode(activity, episodeId, state);
                }
            }).start();
        }
    }

    private void markAllEpisodes(final boolean state) {
        final Activity activity = getActivity();
        if (activity != null) {
            new Thread(new Runnable() {
                public void run() {
                    DBUtils.markSeasonEpisodes(activity, getSeasonId(), state);
                    activity.getContentResolver().notifyChange(Episodes.CONTENT_URI, null);
                }
            }).start();
        }
    }

    private void updatePreferences() {
        sorting = Utils.getEpisodeSorting(getActivity());
    }

    public Loader<Cursor> onCreateLoader(int arg0, Bundle arg1) {
        return new CursorLoader(getActivity(),
                Episodes.buildEpisodesOfSeasonWithShowUri(getSeasonId()), EpisodesQuery.PROJECTION,
                null, null, sorting.query());
    }

    public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
        mAdapter.swapCursor(cursor);
    }

    public void onLoaderReset(Loader<Cursor> arg0) {
        mAdapter.swapCursor(null);
    }

    interface EpisodesQuery {
        String[] PROJECTION = new String[] {
                Tables.EPISODES + "." + Episodes._ID, Episodes.WATCHED, Episodes.TITLE,
                Episodes.NUMBER, Episodes.FIRSTAIRED, Episodes.DVDNUMBER, Shows.AIRSTIME
        };

        int _ID = 0;

        int WATCHED = 1;

        int TITLE = 2;

        int NUMBER = 3;

        int FIRSTAIRED = 4;

        int DVDNUMBER = 5;

        int SHOW_AIRSTIME = 6;
    }

    public static class SortDialogFragment extends DialogFragment {

        /**
         * Creates a new sorting {@link DialogFragment} with posibilities of
         * {@code sortingArray} and selected value set to {@code index}.
         * 
         * @param index
         * @param sortingArray
         * @return
         */
        public static SortDialogFragment newInstance(int index, int sortingArray) {
            SortDialogFragment f = new SortDialogFragment();
            Bundle args = new Bundle();
            args.putInt("index", index);
            args.putInt("sortingarray", sortingArray);
            f.setArguments(args);
            return f;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final CharSequence[] items = getResources().getStringArray(
                    getArguments().getInt("sortingarray"));

            return new AlertDialog.Builder(getActivity())
                    .setTitle(getString(R.string.pref_episodesorting))
                    .setSingleChoiceItems(items, getArguments().getInt("index"),
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int item) {
                                    EpisodesFragment f = (EpisodesFragment) getFragmentManager()
                                            .findFragmentById(R.id.fragment_episodes);
                                    if (f == null) {
                                        f = (EpisodesFragment) getFragmentManager()
                                                .findFragmentById(R.id.root_container);
                                    }
                                    f.updateSorting(item);
                                    dismiss();
                                }
                            }).create();
        }
    }

    private void updateSorting(int item) {
        sorting = (Constants.EpisodeSorting.values())[item];
        AnalyticsUtils.getInstance(getActivity()).trackEvent("Episodes", "Sorting", sorting.name(),
                0);

        SharedPreferences.Editor prefEditor = PreferenceManager.getDefaultSharedPreferences(
                getActivity()).edit();
        prefEditor.putString("episodeSorting",
                (getResources().getStringArray(R.array.epsortingData))[item]);
        prefEditor.commit();
        getLoaderManager().restartLoader(EPISODES_LOADER, null, EpisodesFragment.this);

        if (Build.VERSION.SDK_INT >= 11) {
            getActivity().invalidateOptionsMenu();
        }
    }
}
