package app.com.thetechnocafe.linkshortner.Database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import app.com.thetechnocafe.linkshortner.Models.UrlListModels.AllTime;
import app.com.thetechnocafe.linkshortner.Models.UrlListModels.Analytics;
import app.com.thetechnocafe.linkshortner.Models.UrlListModels.Day;
import app.com.thetechnocafe.linkshortner.Models.UrlListModels.Month;
import app.com.thetechnocafe.linkshortner.Models.UrlListModels.ShortLink;
import app.com.thetechnocafe.linkshortner.Models.UrlListModels.TwoHours;
import app.com.thetechnocafe.linkshortner.Models.UrlListModels.Week;
import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Predicate;
import io.reactivex.schedulers.Schedulers;

/**
 * Created by gurleensethi on 06/03/17.
 */

public class DatabaseAPI {
    private static final String TAG = DatabaseAPI.class.getName();
    private static DatabaseAPI sInstance;
    private DatabaseHelper mDatabaseHelper;

    //Singleton class
    private DatabaseAPI(Context context) {
        mDatabaseHelper = new DatabaseHelper(context);
    }

    //Instance method
    public static DatabaseAPI getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new DatabaseAPI(context);
        }
        return sInstance;
    }

    //Predicate Object to decide whether a Link already exits in the database
    private Predicate<ShortLink> shortLinkAlreadyExistsPredicate = new Predicate<ShortLink>() {
        @Override
        public boolean test(ShortLink shortLink) throws Exception {
            //Get the database
            SQLiteDatabase database = mDatabaseHelper.getReadableDatabase();

            //Create sql query to get the links with the same id
            String selectionSQL = "SELECT * FROM "
                    + DatabaseHelper.SHORT_LINK_TABLE
                    + " WHERE "
                    + DatabaseHelper.COL_SHORT_LINK_ID
                    + " = "
                    + "\"" + shortLink.getId() + "\"";

            Cursor cursor = database.rawQuery(selectionSQL, null);

            boolean result = cursor.getCount() == 0;

            //If exists in database then update details
            if (!result) {
                updateShortLinkDetails(shortLink);
            }

            //Close cursor and database
            cursor.close();

            return result;
        }
    };

    /**
     * Insert a Single Short Link and its Analytics in the Database
     *
     * @param shortLink ShortLink model to be stored
     */
    private void insertLink(ShortLink shortLink) {
        //Create the content values for short link and add data
        ContentValues shortLinkContentValues = new ContentValues();
        shortLinkContentValues.put(DatabaseHelper.COL_SHORT_LINK_KIND, shortLink.getKind());
        shortLinkContentValues.put(DatabaseHelper.COL_SHORT_LINK_ID, shortLink.getId());
        shortLinkContentValues.put(DatabaseHelper.COL_SHORT_LINK_LONG_URL, shortLink.getLongUrl());
        shortLinkContentValues.put(DatabaseHelper.COL_SHORT_LINK_STATUS, shortLink.getStatus());

        //Convert date to long
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

        long time = 0l;
        try {
            time = simpleDateFormat.parse(shortLink.getCreated().split("\\.")[0]).getTime();
        } catch (Exception e) {
            e.printStackTrace();
        }
        shortLinkContentValues.put(DatabaseHelper.COL_SHORT_LINK_CREATED, time);

        //Create the content values for analytics and add data
        ContentValues analyticsContentValues = new ContentValues();
        analyticsContentValues.put(DatabaseHelper.COL_ANALYTICS_ID, shortLink.getId());
        analyticsContentValues.put(DatabaseHelper.COL_ANALYTICS_ALL_TIME, shortLink.getAnalytics().getAllTime().getShortUrlClicks());
        analyticsContentValues.put(DatabaseHelper.COL_ANALYTICS_DAY, shortLink.getAnalytics().getDay().getShortUrlClicks());
        analyticsContentValues.put(DatabaseHelper.COL_ANALYTICS_MONTH, shortLink.getAnalytics().getMonth().getShortUrlClicks());
        analyticsContentValues.put(DatabaseHelper.COL_ANALYTICS_WEEK, shortLink.getAnalytics().getWeek().getShortUrlClicks());
        analyticsContentValues.put(DatabaseHelper.COL_ANALYTICS_TWO_HOURS, shortLink.getAnalytics().getTwoHours().getShortUrlClicks());

        //Insert in database
        //Get the database
        SQLiteDatabase database = mDatabaseHelper.getWritableDatabase();

        database.insert(DatabaseHelper.SHORT_LINK_TABLE, null, shortLinkContentValues);
        database.insert(DatabaseHelper.ANALYTICS_TABLE, null, analyticsContentValues);

        Log.d(TAG, "Stored link : " + shortLink.getId());
    }

    //Insert a Single ShortLink on a IO thread using RxJAVA
    public void insertShortLinkAsync(ShortLink shortLink) {
        //Create an Observable the runs on IO thread to insert the data
        Observable.create((ObservableOnSubscribe<Void>) e -> {
            insertLink(shortLink);
            e.onComplete();
        }).subscribeOn(Schedulers.io()).subscribe();
    }

    //Insert a List of ShortLink on a IO thread using RxJAVA
    public Observable insertShortLinkAsync(List<ShortLink> shortLinksList) {
        Observable observable = Observable.fromIterable(shortLinksList)
                .filter(shortLinkAlreadyExistsPredicate)
                .map(shortLink -> {
                    insertLink(shortLink);
                    return shortLink;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());

        return observable;
    }

    /**
     * Get the list of all the saved short links in the database
     * and return it in a RxObservable
     *
     * @param maxLinks the maximum result that should be returned,
     *                 if 0 then all the links are returned
     */
    public Observable<List<ShortLink>> getSavedShortLinks(int maxLinks) {
        Observable<List<ShortLink>> observable = Observable.create(emitter -> {
            //Get the database
            SQLiteDatabase database = mDatabaseHelper.getReadableDatabase();

            //Create a list of short links
            List<ShortLink> shortLinksList = new ArrayList<>();

            //SQL to get all links
            String shortLinkSQL = "SELECT * FROM " + DatabaseHelper.SHORT_LINK_TABLE +
                    " ORDER BY " + DatabaseHelper.COL_SHORT_LINK_CREATED + " DESC";

            if (maxLinks != 0) {
                shortLinkSQL += " LIMIT " + maxLinks;
            }

            //SQL to get all analytics of a short link (replace the {short_link_id} with the id of short link)
            String analyticsSQL = "SELECT * FROM " + DatabaseHelper.ANALYTICS_TABLE
                    + " WHERE " + DatabaseHelper.COL_ANALYTICS_ID + " = \"{short_link_id}\"";

            //Run the query and get the cursor
            Cursor shortLinkCursor = database.rawQuery(shortLinkSQL, null);

            //Loop and get all values
            while (shortLinkCursor.moveToNext()) {
                ShortLink shortLink = new ShortLink();

                //Get the id of short link
                String shortLinkID = shortLinkCursor.getString(shortLinkCursor.getColumnIndex(DatabaseHelper.COL_SHORT_LINK_ID));

                shortLink.setId(shortLinkID);
                shortLink.setCreated(shortLinkCursor.getString(shortLinkCursor.getColumnIndex(DatabaseHelper.COL_SHORT_LINK_CREATED)));
                shortLink.setKind(shortLinkCursor.getString(shortLinkCursor.getColumnIndex(DatabaseHelper.COL_SHORT_LINK_KIND)));
                shortLink.setLongUrl(shortLinkCursor.getString(shortLinkCursor.getColumnIndex(DatabaseHelper.COL_SHORT_LINK_LONG_URL)));
                shortLink.setStatus(shortLinkCursor.getString(shortLinkCursor.getColumnIndex(DatabaseHelper.COL_SHORT_LINK_STATUS)));

                //Get the cursor for analytics corresponding to the particular shortLink ID
                Cursor analyticsCursor = database.rawQuery(analyticsSQL.replace("{short_link_id}", shortLinkID), null);

                //Loop and get the analytics object
                while (analyticsCursor.moveToNext()) {
                    Analytics analytics = new Analytics();

                    AllTime allTime = new AllTime();
                    allTime.setShortUrlClicks(analyticsCursor.getString(analyticsCursor.getColumnIndex(DatabaseHelper.COL_ANALYTICS_ALL_TIME)));
                    Day day = new Day();
                    day.setShortUrlClicks(analyticsCursor.getString(analyticsCursor.getColumnIndex(DatabaseHelper.COL_ANALYTICS_DAY)));
                    Month month = new Month();
                    month.setShortUrlClicks(analyticsCursor.getString(analyticsCursor.getColumnIndex(DatabaseHelper.COL_ANALYTICS_MONTH)));
                    TwoHours twoHours = new TwoHours();
                    twoHours.setShortUrlClicks(analyticsCursor.getString(analyticsCursor.getColumnIndex(DatabaseHelper.COL_ANALYTICS_TWO_HOURS)));
                    Week week = new Week();
                    week.setShortUrlClicks(analyticsCursor.getString(analyticsCursor.getColumnIndex(DatabaseHelper.COL_ANALYTICS_WEEK)));

                    analytics.setAllTime(allTime);
                    analytics.setDay(day);
                    analytics.setMonth(month);
                    analytics.setTwoHours(twoHours);
                    analytics.setWeek(week);

                    shortLink.setAnalytics(analytics);
                }

                //Close the cursor
                analyticsCursor.close();

                //Add shortLink to list
                shortLinksList.add(shortLink);
            }

            //Close the cursor
            shortLinkCursor.close();

            emitter.onNext(shortLinksList);
            emitter.onComplete();
        });

        return observable.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread());
    }

    /**
     * Delete all the data in both tables
     */
    public void deleteAllData() {
        Observable.create((ObservableOnSubscribe<Void>) e -> {
            //Get the database
            SQLiteDatabase database = mDatabaseHelper.getReadableDatabase();

            database.delete(DatabaseHelper.SHORT_LINK_TABLE, null, null);
            database.delete(DatabaseHelper.ANALYTICS_TABLE, null, null);

        }).subscribe();
    }

    /**
     * Get the total number of clicks for the links, return a RxObservable
     */
    public Observable<Integer> getTotalClicks() {
        Observable<Integer> observable = Observable.create(emitter -> {
            int totalClicks = 0;

            //Get the database
            SQLiteDatabase database = mDatabaseHelper.getReadableDatabase();

            //SQL to get all links
            String shortLinkSQL = "SELECT * FROM " + DatabaseHelper.SHORT_LINK_TABLE;

            //SQL to get all analytics of a short link (replace the {short_link_id} with the id of short link)
            String analyticsSQL = "SELECT * FROM " + DatabaseHelper.ANALYTICS_TABLE
                    + " WHERE " + DatabaseHelper.COL_ANALYTICS_ID + " = \"{short_link_id}\"";

            //Run the query and get the cursor
            Cursor shortLinkCursor = database.rawQuery(shortLinkSQL, null);

            //Loop and get all values
            while (shortLinkCursor.moveToNext()) {

                //Get the id of short link
                String shortLinkID = shortLinkCursor.getString(shortLinkCursor.getColumnIndex(DatabaseHelper.COL_SHORT_LINK_ID));

                //Get the cursor for analytics corresponding to the particular shortLink ID
                Cursor analyticsCursor = database.rawQuery(analyticsSQL.replace("{short_link_id}", shortLinkID), null);

                //Loop and get the analytics object
                while (analyticsCursor.moveToNext()) {
                    String count = analyticsCursor.getString(analyticsCursor.getColumnIndex(DatabaseHelper.COL_ANALYTICS_ALL_TIME));
                    totalClicks += Integer.parseInt(count);
                }

                //Close the cursor
                analyticsCursor.close();
            }

            //Close the cursor
            shortLinkCursor.close();

            emitter.onNext(totalClicks);
            emitter.onComplete();
        });

        return observable.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread());
    }

    /**
     * Update an already existing short link
     *
     * @param shortLink Short link with updated details
     */
    private void updateShortLinkDetails(ShortLink shortLink) {
        //Create the content values for short link and add data
        ContentValues shortLinkContentValues = new ContentValues();
        shortLinkContentValues.put(DatabaseHelper.COL_SHORT_LINK_ID, shortLink.getId());
        shortLinkContentValues.put(DatabaseHelper.COL_SHORT_LINK_LONG_URL, shortLink.getLongUrl());
        shortLinkContentValues.put(DatabaseHelper.COL_SHORT_LINK_STATUS, shortLink.getStatus());

        //Convert date to long
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

        long time = 0l;
        try {
            time = simpleDateFormat.parse(shortLink.getCreated().split("\\.")[0]).getTime();
        } catch (Exception e) {
            e.printStackTrace();
        }
        shortLinkContentValues.put(DatabaseHelper.COL_SHORT_LINK_CREATED, time);

        //Create the content values for analytics and add data
        ContentValues analyticsContentValues = new ContentValues();
        analyticsContentValues.put(DatabaseHelper.COL_ANALYTICS_ALL_TIME, shortLink.getAnalytics().getAllTime().getShortUrlClicks());
        analyticsContentValues.put(DatabaseHelper.COL_ANALYTICS_DAY, shortLink.getAnalytics().getDay().getShortUrlClicks());
        analyticsContentValues.put(DatabaseHelper.COL_ANALYTICS_MONTH, shortLink.getAnalytics().getMonth().getShortUrlClicks());
        analyticsContentValues.put(DatabaseHelper.COL_ANALYTICS_WEEK, shortLink.getAnalytics().getWeek().getShortUrlClicks());
        analyticsContentValues.put(DatabaseHelper.COL_ANALYTICS_TWO_HOURS, shortLink.getAnalytics().getTwoHours().getShortUrlClicks());

        //Insert in database
        //Get the database
        SQLiteDatabase database = mDatabaseHelper.getWritableDatabase();

        database.update(DatabaseHelper.SHORT_LINK_TABLE, shortLinkContentValues, DatabaseHelper.COL_SHORT_LINK_ID + " = ?", new String[]{shortLink.getId()});
        database.update(DatabaseHelper.ANALYTICS_TABLE, analyticsContentValues, DatabaseHelper.COL_ANALYTICS_ID + " = ?", new String[]{shortLink.getId()});
    }

    /**
     * Get the total number of shortned links in the database
     */
    public Observable<Integer> getTotalShortenedLinks() {
        Observable<Integer> observable = Observable.create(emitter -> {
            int totalLinks = 0;

            //Get the database
            SQLiteDatabase database = mDatabaseHelper.getReadableDatabase();

            //SQL to get all links
            String shortLinkSQL = "SELECT * FROM " + DatabaseHelper.SHORT_LINK_TABLE;

            //Run the query and get the cursor
            Cursor shortLinkCursor = database.rawQuery(shortLinkSQL, null);

            totalLinks = shortLinkCursor.getCount();

            //Close the cursor
            shortLinkCursor.close();

            emitter.onNext(totalLinks);
            emitter.onComplete();
        });

        return observable.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread());
    }
}