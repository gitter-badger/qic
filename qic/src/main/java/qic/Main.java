/*
 * Copyright (C) 2015 thirdy
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package qic;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.joining;
import static org.apache.commons.lang3.StringUtils.substringAfter;
import static qic.Command.Status.ERROR;

import java.awt.BorderLayout;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import qic.Command.Status;
import qic.SearchPageScraper.SearchResultItem;
import qic.util.CommandLine;
import qic.util.SessProp;
import qic.util.Util;

/**
 * @author thirdy
 *
 */
public class Main {
	
	public static BlackmarketLanguage language;
	BackendClient backendClient = new BackendClient();
	SessProp sessProp = new SessProp();

	public static void main(String[] args) throws Exception {
		System.out.println("QIC (Quasi-In-Chat) Search 0.2");
		System.out.println("QIC is 100% free and open source licensed under GPLv2");
		System.out.println("Created by the contributors of: https://github.com/poeqic");
		System.out.println();
		System.out.println("Project Repo: https://github.com/poeqic/qic");
		System.out.println("Project Page: https://github.com/poeqic/qic");
		System.out.println();
		System.out.println("QIC is fan made tool and is not affiliated with Grinding Gear Games in any way.");

		try {
			reloadConfig();
			new Main(args);
		} catch (Exception e) {
			JOptionPane.showMessageDialog(null, "Error occured: " + e.getMessage());
			throw e;
		}
    }

	private static void reloadConfig() throws IOException, FileNotFoundException {
		language = new BlackmarketLanguage();
	}

	public Main(String[] args) throws IOException, InterruptedException {
		CommandLine cmd = new CommandLine(args);
		boolean guiEnabled = cmd.hasFlag("-gui");
		guiEnabled = guiEnabled || cmd.getNumberOfArguments() == 0;

		System.out.println("guiEnabled: " + guiEnabled);
		
		if (guiEnabled) {
			showGui(cmd.getArgument(0));
		} else {
			if (cmd.getNumberOfArguments() == 0) {
				throw new IllegalArgumentException("First arguement needed, and should be the query. e.g. 'search chest 100life 6s5L'. "
						+ "Enclosed in double quoutes if needed.");
			}
			String query = cmd.getArgument(0);
			System.out.println("Query: " + query);
			
			Command command = processLine(query);
			String json = command.toJson();
			writeToFile(json);
		}
	}

	private void showGui(String query) {
		JFrame frame = new JFrame("QIC Search - Simple GUI");
		frame.setLayout(new BorderLayout(5, 5));
		
		RSyntaxTextArea textArea = new RSyntaxTextArea(20, 60);
		textArea.setText("Enter a command in the textfield below then press Enter..");
	    textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
	    textArea.setCodeFoldingEnabled(true);
	    RTextScrollPane sp = new RTextScrollPane(textArea);
		
		JTextField tf = new JTextField(100);
		frame.getContentPane().add(new JScrollPane(sp), BorderLayout.CENTER);
		frame.getContentPane().add(tf, BorderLayout.SOUTH);
		frame.setSize(1000, 700);
		frame.setLocationRelativeTo(null);
		
		tf.setText("search bo tmpsc gloves 4L 60res");
		if (query != null) {
			tf.setText(query);
		}
		
		tf.addActionListener(e -> {
			try {
				String tfText = tf.getText();
				textArea.setText("Running command: " + tfText);
				Command command = processLine(tfText);
				String json = command.toJson();
				textArea.setText(json);
				writeToFile(json);
			} catch (Exception ex) {
				String stackTrace = ExceptionUtils.getStackTrace(ex);
				textArea.setText(stackTrace);
			}
		});
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setVisible(true);
	}

	private void writeToFile(String contents) throws IOException {
		String jsonFile = "results.json";
		File file = new File(jsonFile);
		FileUtils.writeStringToFile(file , contents, "UTF-8", false);
	}

	private Command processLine(String line) throws IOException {
		Command command = new Command(line);

		try {
			if (line.equalsIgnoreCase("searchend") || line.equalsIgnoreCase("se")) {
				command.status = Status.EXIT;
				sessProp.clear();
			} else if (line.equalsIgnoreCase("reload")) {
				reloadConfig();
			} else if (line.startsWith("sort")&& !sessProp.getLocation().isEmpty()) {
				command.itemResults = runSearch(line, true);
			} else if (line.startsWith("search")) {
				String terms = substringAfter(line, "search").trim();
				if (!terms.isEmpty()) {
					command.itemResults = runSearch(terms, false);
				}
			} else if (line.startsWith("s ")) {
				String terms = substringAfter(line, "s ").trim();
				if (!terms.isEmpty()) {
					command.itemResults = runSearch(terms, false);
				}
			}
			command.league = sessProp.getLeague();
		} catch (Exception e) {
			e.printStackTrace();
			command.status = ERROR;
			command.errorShort = e.getMessage();
			command.errorStackTrace = ExceptionUtils.getStackTrace(e);
		}
		return command;
	}

	private List<SearchResultItem> runSearch(String terms, boolean sortOnly) throws Exception {
		String html = downloadHtml(terms, sortOnly);
		SearchPageScraper scraper = new SearchPageScraper(html);
		List<SearchResultItem> items = scraper.parse();
		System.out.println("items found: " + items.size());
		return items;
	}

	public String downloadHtml(String terms, boolean sortOnly) throws Exception {
		long start = System.currentTimeMillis();
		
		String regex = "([^\\s]*=\".*?\")";
		List<String> customHttpKeyVals = Util.regexMatches(regex, terms, 1);
		String customHttpKeyVal = customHttpKeyVals.stream()
				.map(s -> StringUtils.remove(s, '"'))
				.collect(Collectors.joining("&")); 
		String query = terms.replaceAll(regex, " ");
		
		String sort  = language.parseSortToken(query);

		if (!sortOnly) {
			System.out.println("Query: " + query);
			String payload = language.parse(query);
			payload = asList(payload, customHttpKeyVal).stream().filter(StringUtils::isNotBlank).collect(joining("&"));
			System.out.println("Unencoded payload: " + payload);
			payload = asList(payload.split("&")).stream().map(Util::encodeQueryParm).collect(joining("&"));
			String location  = submitSearchForm(payload);
			String league = language.parseLeagueToken(query);
			sessProp.setLocation(location);
			sessProp.setLeague(league);
			sessProp.saveToFile();
		}

		System.out.println("sort: " + sort);
		String searchPage = ajaxSort(sort);
		long end = System.currentTimeMillis();

		long duration = end - start;
		System.out.println("Took " + duration + " ms");
		// Add a bit of delay, just in case
		Thread.sleep(30);
		return searchPage;
	}

	private String ajaxSort(String sort) throws Exception {
		String searchPage = "";
		sort = URLEncoder.encode(sort, "UTF-8");
		sort = "sort=" + sort + "&bare=true";
		searchPage = backendClient.postXMLHttpRequest(sessProp.getLocation(), sort);
		return searchPage;
	}

	private String submitSearchForm(String payload) throws Exception {
		String url = "http://poe.trade/search";
		String location = backendClient.post(url, payload);
		return location;
	}


}
