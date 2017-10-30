package demo.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;

import com.fasterxml.jackson.jr.ob.JSON;

public class MixServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	private static Map<String, List<String>> addressMap = new HashMap<String, List<String>>();
	private static Map<String, BigDecimal> coinMap = new HashMap<String, BigDecimal>();
	private static String mixingAddress = UUID.randomUUID().toString();
	
	private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	private static final BigDecimal maxTransactionAmount = new BigDecimal(25);
	private static final BigDecimal transactionVariation = new BigDecimal(10);
	
	private static final double transferDelayRange = 10;
	private static final double minTransferDelayRange = 2;
	
	private static final Random random = new Random();
	
	// Create process to poll for transfers to holding addresses 
	private static final Runnable checkForNewCoins = new Runnable() {
		@Override public void run() {
			for (final String address : addressMap.keySet()) {
				final BigDecimal balance = getAccountBalance(address);
				if (balance.compareTo(new BigDecimal(0)) > 0) {
					// If there are any coins at that address, schedule a job to transfer the coin to the mixing account and add it to the map
					scheduler.schedule(new Runnable() {
						@Override
						public void run() {
							// Transfer some coin to the mixing account and increase the amount owed to them in the map
							BigDecimal transferAmount = getAmountToTransferInCurrentBatch(balance);
							if (transferCoin(address, mixingAddress, transferAmount)) {
								coinMap.put(address, coinMap.containsKey(address) ? coinMap.get(address).add(transferAmount) : transferAmount);
							}
						}
					}, (long) (Math.random() * transferDelayRange + minTransferDelayRange), TimeUnit.SECONDS);
				}
			}
		}
	};
	private static final ScheduledFuture<?> newCoinHandler = scheduler.scheduleAtFixedRate(checkForNewCoins, 5, 5, TimeUnit.SECONDS);
	
	// Create a process to check the coin map and schedule outgoing transactions
	private static final Runnable checkCoinMap = new Runnable() {
		@Override public void run() {
			for (final Entry<String, BigDecimal> entry : coinMap.entrySet()) {
				if (entry.getValue().compareTo(new BigDecimal(0)) > 0) {
					// If there are any coins, pick some to transfer to one of the addresses at random.
					// If there are more than the max amount, we will transfer more the next time this runs
					final String holdingAddress = entry.getKey();
					List<String> addresses = addressMap.get(holdingAddress);
					final BigDecimal transferAmount = getAmountToTransferInCurrentBatch(entry.getValue());
					final String destinationAddress = addresses.get(random.nextInt(addresses.size()));
					
					scheduler.schedule(new Runnable() {
						@Override
						public void run() {
							// Transfer some coin to the mixing account and increase the amount owed to them in the map		
							if (transferCoin(mixingAddress, destinationAddress, transferAmount)) {
								coinMap.put(holdingAddress, coinMap.get(holdingAddress).subtract(transferAmount));
							}
						}
					}, (long) (random.nextDouble() * transferDelayRange + minTransferDelayRange), TimeUnit.SECONDS);
				}
			}
		}
	};
	private static final ScheduledFuture<?> coinMapHandler = scheduler.scheduleAtFixedRate(checkCoinMap, 5, 5, TimeUnit.SECONDS);
	
	/**
	 * Look up the balance of an account from the jobcoin server
	 * @param address The address to look up
	 * @return The balance of the account
	 */
	private static BigDecimal getAccountBalance(String address) {
		// Do a get on http://jobcoin.gemini.com/favorite/api/addresses/{address}
		// Parse the result as json and get balance as a string, parse to BigDecimal
		HttpClient httpclient = new DefaultHttpClient();
        try {
        	HttpGet httpGet = new HttpGet("http://jobcoin.gemini.com/favorite/api/addresses/" + address);
            HttpResponse getResponse = httpclient.execute(httpGet);
            String json = new BufferedReader(new InputStreamReader(getResponse.getEntity().getContent()))
            		  .lines().collect(Collectors.joining("\n"));
            Map<String, Object> map = JSON.std.mapFrom(json);
            return new BigDecimal(map.get("balance").toString());
        } catch (Exception e) {
        	e.printStackTrace();
        	return new BigDecimal(0);
		} finally {
            httpclient.getConnectionManager().shutdown();
        }
	}
	
	/**
	 * Transfers coins 
	 * @param from source address
	 * @param to destination address
	 * @param amount The amount of coins
	 * @return True if a 200 is returned from the server
	 */
	private static boolean transferCoin(String from, String to, BigDecimal amount) {
		// Do a post to http://jobcoin.gemini.com/favorite/api/transactions
		// Parameters are fromAddress, toAddress, and amount all strings
        HttpClient httpclient = new DefaultHttpClient();
        try {
            HttpPost httpPost = new HttpPost("http://jobcoin.gemini.com/favorite/api/transactions");
            ArrayList<NameValuePair> parameters = new ArrayList<NameValuePair>();
            parameters.add(new BasicNameValuePair("fromAddress", from));
            parameters.add(new BasicNameValuePair("toAddress", to));
            parameters.add(new BasicNameValuePair("amount", amount.toPlainString()));
            httpPost.setEntity(new UrlEncodedFormEntity(parameters, Charset.forName("UTF-8")));
            
            HttpResponse postResponse = httpclient.execute(httpPost);
            return postResponse.getStatusLine().getStatusCode() == HttpServletResponse.SC_OK;
        } catch (Exception e) {
        	e.printStackTrace();
        	return false;
		} finally {
            httpclient.getConnectionManager().shutdown();
        }
	}
	
	/**
	 * Get a random amount to transfer
	 * @param amount The amount of the account
	 * @return A random amount rounded down to 2 decimal points or the value of the account, whichever is less
	 */
	private static BigDecimal getAmountToTransferInCurrentBatch(BigDecimal amount) {
		BigDecimal maxValue = maxTransactionAmount.subtract(transactionVariation.multiply(new BigDecimal(random.nextDouble()).setScale(2, RoundingMode.DOWN)));
		return amount.min(maxValue);
	}
	
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
        	// The user provides a list of new, unused addresses that they own to the mixer
        	String addressesParam = request.getParameter("addresses");
        	
        	List<String> addressList = JSON.std.listOfFrom(String.class, addressesParam);
        	        	
        	// Create a new unique identifier for the user to send coin to
        	String uniqueID = UUID.randomUUID().toString();
        	
        	// Assign the mapping in the address map
        	addressMap.put(uniqueID, addressList);
        	
        	// Return the unique identifier
  			setResponseInfo(response, HttpServletResponse.SC_OK, uniqueID);
        } catch (Exception e) {
        	e.printStackTrace();
        	setResponseInfo(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "");
		}
	}
	
    public static void setResponseInfo(HttpServletResponse response, int status, String body) {
    	response.setContentType("text/html");
    	response.setStatus(status);
    	PrintWriter writer = null;
		try {
			writer = response.getWriter();
			writer.write(body);
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (writer != null) {
				writer.close();
			}
		}
    }
}
