package org.knoesis.twitter.crawler;



import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.knoesis.models.AnnotatedTweet;
import org.knoesis.twarql.extractions.Extractor;
import org.knoesis.twarql.extractions.TagExtractor;
import org.knoesis.twarql.extractions.TweetProcessor;
import org.knoesis.utils.Utils;

import twitter4j.Query;
import twitter4j.QueryResult;
import twitter4j.Tweet;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.TwitterFactory;
/**
 * The objective of this class is to get the last 1500 tweets for 
 * a given keyword and support the analysis for consideration of 
 * the hashtag.
 * 
 * @author pavan
 *
 */
public class SearchTwitter {
	/*Defining required classes for init()*/
	private Twitter twitter = null;
	private static TweetProcessor processor = null;
	private List<AnnotatedTweet> annotatedTweets = null;

	public SearchTwitter(List<Extractor> extractors) {
		processor = new TweetProcessor(extractors);
	}
	/**
	 * Processes the tweets based on the extractors provided and transforms 
	 * the Tweet to AnnotatedTweet (org.knoesis.model). 
	 * 
	 * TODO: For now the list of extractors are just TagExtractor.
	 * 
	 * Pramod -- Changed this method to return the list of Annotated Tweets.
	 * @param tag
	 * 
	 * flag 1 : isHashTag -- true: Means crawl for tweets having tag as Hashtag
	 *                       false: Meanse crawl for tweets having tag as a keyword.
	 * flag 2 : storeToDB -- true: Store the tweets into the DB.
	 *                       false: do not store.                      
	 * 
	 */
	public List<AnnotatedTweet> getTweets(String tag, boolean isHashTag, boolean storeToDB) {
		Twitter twitter = new TwitterFactory().getInstance();
		Query query = new Query(tag); 	// Query for the search
		query.setRpp(100);	//default is 15 tweets/search which is set to 100
		QueryResult result = null;
		List<AnnotatedTweet> tweets = new ArrayList<AnnotatedTweet>();
		List<Tweet> tweetsFromAPI = null;
		Connection conn = null;
		
		// Getting the connection
		if(storeToDB)
			conn = getConnectionToDB("XXXX", "XXXXX");
		
		for(int i=1; i<=15; i++){
			query.setPage(i);
			try {
				//System.out.println(query);
				//System.out.println(twitter.search(query));
				result = twitter.search(query);
			} catch (TwitterException e) {
				// Waiting for 10 mins and querying. Reason: Twitter search API has rate limitations
				// So if there is an exception wait for 10 min and query again
				// TODO: Find a better way to fix this.
				Utils.sleep(10*60);
				try {
					result = twitter.search(query);
				} catch (TwitterException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
			}
			
			if (result.getTweets().isEmpty())
				break;
			else{
				tweetsFromAPI = result.getTweets();
				tweets.addAll(processor.process(TweetFactory.Tweet2AnnotatedTweet(tweetsFromAPI, tag, isHashTag)));
				
				if(storeToDB){
					// The eventID will be Hash_ followed by the Hashtag.		
					storeIntoDB(tweetsFromAPI, "Hash_"+ tag,conn);
				}
			}
		}
		
		//System.out.println(tweets);
		System.out.println(tweets.size());
		return tweets;

	}
	
	/**
	 * Getting connection for the database
	 * @param username
	 * @param password
	 * @return
	 */
	public Connection getConnectionToDB(String username,String password){
		Connection conn = null;
		System.out.println(new Date() + " Connecting to Database");
		String url = "jdbc:mysql://130.108.5.96/continuous_semantics?user=" + username + "&password=" + password;
		
		try {
			Class.forName("com.mysql.jdbc.Driver").newInstance();
			conn = DriverManager.getConnection(url);
		} catch (InstantiationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		System.out.println("Connected to database");
		return conn;
		
	}
	
	/**
	 * This method stores the tweets into DB.
	 * @param tweets
	 * @param eventID
	 */
	public void storeIntoDB(List<Tweet> tweets, String eventID, Connection conn){
		
		// Please replace this with the correct username and password.
		PreparedStatement ps = null;
		double latitude = 10000;
		double longitude = 10000;
		
		// ==PreparedStatement pstmt;=========== twitterdata table ===============
		String sql = "INSERT IGNORE INTO `continuous_semantics`.`twitterdata` "
				+ "(`twitter_ID`, `tweet`, `eventID`, `published_date`, "
				+ "`twitter_author`, `latitude`, `longitude`) "
				+ "VALUES ( ?, ?, ?, ?, ?, ?, ? );";
					
		try {
			conn.setAutoCommit(false);
			ps = conn.prepareStatement(sql);
		
			for(Tweet tweet : tweets){
				String tweetContent = tweet.getText();
				String twitterID = String.valueOf(tweet.getId());
				String twitter_author = tweet.getFromUser();
				Date published_date = tweet.getCreatedAt();
				if(tweet.getGeoLocation() != null)
				{
					latitude = tweet.getGeoLocation().getLatitude();
					longitude = tweet.getGeoLocation().getLongitude();
				}			
				
					ps.setString(1, twitterID);
					ps.setString(2, tweetContent);
					ps.setString(3, eventID);
					
					// hard fix for now; trying to set time stamp to GMT timezone
					ps.setTimestamp(4, new java.sql.Timestamp(published_date.getTime()
							+ (long) 1000 * 60 * 60 * 5));
					ps.setString(5, twitter_author);
					ps.setFloat(6, (float)latitude);
					ps.setFloat(7, (float)longitude);
					
					ps.addBatch();
				
			}
		
			int[] addCount = ps.executeBatch();
			conn.commit();
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public static void main(String[] args) {
		List<Extractor> extractors = new ArrayList<Extractor>();
		extractors.add(new TagExtractor());
		SearchTwitter searchTwitter = new SearchTwitter(extractors);
		List<AnnotatedTweet> aTweets = searchTwitter.getTweets("#election2012", false, false);
		Map<String, Integer> tags = new HashMap<String, Integer>();
		for(AnnotatedTweet aTweet: aTweets){
			for(String tag: aTweet.getHashtags()){
				if(tags.keySet().contains(tag.toLowerCase()))
					tags.put(tag.toLowerCase(), tags.get(tag.toLowerCase())+1);
				else
					tags.put(tag.toLowerCase(), 1);
			}
		}
		System.out.println(tags);
	}

}