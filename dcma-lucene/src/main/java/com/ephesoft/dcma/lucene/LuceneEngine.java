/********************************************************************************* 
* Ephesoft is a Intelligent Document Capture and Mailroom Automation program 
* developed by Ephesoft, Inc. Copyright (C) 2010-2011 Ephesoft Inc. 
* 
* This program is free software; you can redistribute it and/or modify it under 
* the terms of the GNU Affero General Public License version 3 as published by the 
* Free Software Foundation with the addition of the following permission added 
* to Section 15 as permitted in Section 7(a): FOR ANY PART OF THE COVERED WORK 
* IN WHICH THE COPYRIGHT IS OWNED BY EPHESOFT, EPHESOFT DISCLAIMS THE WARRANTY 
* OF NON INFRINGEMENT OF THIRD PARTY RIGHTS. 
* 
* This program is distributed in the hope that it will be useful, but WITHOUT 
* ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS 
* FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more 
* details. 
* 
* You should have received a copy of the GNU Affero General Public License along with 
* this program; if not, see http://www.gnu.org/licenses or write to the Free 
* Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 
* 02110-1301 USA. 
* 
* You can contact Ephesoft, Inc. headquarters at 111 Academy Way, 
* Irvine, CA 92617, USA. or at email address info@ephesoft.com. 
* 
* The interactive user interfaces in modified source and object code versions 
* of this program must display Appropriate Legal Notices, as required under 
* Section 5 of the GNU Affero General Public License version 3. 
* 
* In accordance with Section 7(b) of the GNU Affero General Public License version 3, 
* these Appropriate Legal Notices must retain the display of the "Ephesoft" logo. 
* If the display of the logo is not reasonably feasible for 
* technical reasons, the Appropriate Legal Notices must display the words 
* "Powered by Ephesoft". 
********************************************************************************/ 

package com.ephesoft.dcma.lucene;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.demo.IndexHTML;
import org.apache.lucene.demo.SearchFiles;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.similar.MoreLikeThis;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.ephesoft.dcma.batch.dao.impl.BatchPluginPropertyContainer.BatchPluginConfiguration;
import com.ephesoft.dcma.batch.schema.Batch;
import com.ephesoft.dcma.batch.schema.DocField;
import com.ephesoft.dcma.batch.schema.Document;
import com.ephesoft.dcma.batch.schema.Field;
import com.ephesoft.dcma.batch.schema.HocrPages;
import com.ephesoft.dcma.batch.schema.Page;
import com.ephesoft.dcma.batch.schema.DocField.AlternateValues;
import com.ephesoft.dcma.batch.schema.HocrPages.HocrPage;
import com.ephesoft.dcma.batch.schema.Page.PageLevelFields;
import com.ephesoft.dcma.batch.service.BatchSchemaService;
import com.ephesoft.dcma.batch.service.PluginPropertiesService;
import com.ephesoft.dcma.core.TesseractVersionProperty;
import com.ephesoft.dcma.core.common.DCMABusinessException;
import com.ephesoft.dcma.core.common.FileType;
import com.ephesoft.dcma.core.component.ICommonConstants;
import com.ephesoft.dcma.core.exception.DCMAApplicationException;
import com.ephesoft.dcma.core.threadpool.BatchInstanceThread;
import com.ephesoft.dcma.core.threadpool.ProcessExecutor;
import com.ephesoft.dcma.da.dao.BatchInstanceDao;
import com.ephesoft.dcma.da.service.BatchClassPluginConfigService;
import com.ephesoft.dcma.da.service.PageTypeService;
import com.ephesoft.dcma.util.ApplicationConfigProperties;
import com.ephesoft.dcma.util.CustomFileFilter;
import com.ephesoft.dcma.util.FileUtils;
import com.ephesoft.dcma.util.OSUtil;

/**
 * This class generate the confidence score by using Lucene search engine.It first creates the indexes for a well defined set of input
 * HOCR files and then finds first a maximum of 5 similar documents in the standard indexed set based on confidence score. It stores
 * the first in page level field and others as alternate values inside batch xml.
 * 
 * @author Ephesoft
 * @version 1.0
 * @see com.ephesoft.dcma.core.component.ICommonConstants
 * 
 */
@Component
public class LuceneEngine implements ICommonConstants {

	private static final String RECOSTAR_HOCR_PLUGIN = "RECOSTAR_HOCR";
	private static final String SEARCH_CLASSIFICATION_PLUGIN = "SEARCH_CLASSIFICATION";
	private static final String TESSERACT_HOCR_PLUGIN = "TESSERACT_HOCR";
	/**
	 * Constant for file type name.
	 */
	public static final String FIELD_TYPE_NAME = "Search_Engine_Classification";
	/**
	 * Constant for recostar base path of executables.
	 */
	public static final String RECOSTAR_BASE_PATH = "RECOSTAR_PATH";
	/**
	 * Constant for tif file extension.
	 */
	public static final String TIF_FILE_EXT = ".tif";
	/**
	 * Constant for png file extension.
	 */
	public static final String PNG_FILE_EXT = ".png";
	/**
	 * Constant for License verifier class.
	 */
	public static final String LICENSE_VERIFIER = "com.ephesoft.license.client.LicenseClientExecutor";
	/**
	 * Constant for switch
	 */
	private static final String ON = "ON";

	/**
	 * Instance of BatchSchemaService.
	 */
	@Autowired
	private BatchSchemaService batchSchemaService;

	/**
	 * Instance of BatchInstanceDao.
	 */
	@Autowired
	private BatchInstanceDao batchInstanceDao;

	/**
	 * Instance of PluginPropertiesService.
	 */
	@Autowired
	@Qualifier("batchInstancePluginPropertiesService")
	private PluginPropertiesService pluginPropertiesService;

	/**
	 * Instance of BatchClassPluginPropertiesService.
	 */
	@Autowired
	@Qualifier("batchClassPluginPropertiesService")
	private PluginPropertiesService batchClassPluginPropertiesService;

	/**
	 * Instance of PluginPropertiesService.
	 */
	@Autowired
	private BatchClassPluginConfigService batchClassPluginConfigService;

	/**
	 * Instance of PageTypeService.
	 */
	@Autowired
	private PageTypeService pageTypeService;

	/**
	 * Logger instance for logging using slf4j for logging information.
	 */
	private Logger logger = LoggerFactory.getLogger(LuceneEngine.class);

	/**
	 * First page.
	 */
	private transient String firstPage;
	/**
	 * Middle page.
	 */
	private transient String middlePage;
	/**
	 * Last page.
	 */
	private transient String lastPage;

	/**
	 * @param firstPage the firstPage to set
	 */
	public void setFirstPage(final String firstPage) {
		this.firstPage = firstPage;
	}

	/**
	 * @param middlePage the middlePage to set
	 */
	public void setMiddlePage(final String middlePage) {
		this.middlePage = middlePage;
	}

	/**
	 * @param lastPage the lastPage to set
	 */
	public void setLastPage(final String lastPage) {
		this.lastPage = lastPage;
	}

	/**
	 * @return the batchSchemaService
	 */
	public BatchSchemaService getBatchSchemaService() {
		return batchSchemaService;
	}

	/**
	 * @param batchSchemaService the batchSchemaService to set
	 */
	public void setBatchSchemaService(final BatchSchemaService batchSchemaService) {
		this.batchSchemaService = batchSchemaService;
	}

	/**
	 * @return the pageTypeService
	 */
	public PageTypeService getPageTypeService() {
		return pageTypeService;
	}

	/**
	 * @param pageTypeService the pageTypeService to set
	 */
	public void setPageTypeService(final PageTypeService pageTypeService) {
		this.pageTypeService = pageTypeService;
	}

	/**
	 * @return the batchClassPluginConfigService
	 */
	public BatchClassPluginConfigService getBatchClassPluginConfigService() {
		return batchClassPluginConfigService;
	}

	/**
	 * @param batchClassPluginConfigService the batchClassPluginConfigService to set
	 */
	public void setBatchClassPluginConfigService(final BatchClassPluginConfigService batchClassPluginConfigService) {
		this.batchClassPluginConfigService = batchClassPluginConfigService;
	}

	/**
	 * @return the batchInstanceDao
	 */
	public BatchInstanceDao getBatchInstanceDao() {
		return batchInstanceDao;
	}

	/**
	 * @param batchInstanceDao the batchInstanceDao to set
	 */
	public void setBatchInstanceDao(final BatchInstanceDao batchInstanceDao) {
		this.batchInstanceDao = batchInstanceDao;
	}

	/**
	 * This method generates the confidence score for each HOCR file read from batch xml and stores maximum of 5 best fir pages inbatch
	 * xml. For comparison it refers to the already created indexes of standard HOCR pages stored in hierarchy : batch class > document
	 * type > page type.
	 * 
	 * @param batchInstanceID String
	 * @throws DCMAApplicationException
	 * @throws DCMABusinessException
	 */
	public void generateConfidence(final String batchInstanceID) throws DCMAApplicationException {
		// Initialize properties
		String switchValue = pluginPropertiesService.getPropertyValue(batchInstanceID, SEARCH_CLASSIFICATION_PLUGIN,
				LuceneProperties.LUCENE_SWITCH);
		if (switchValue.equalsIgnoreCase(ON)) {
			logger.info("Initializing properties...");
			String validExt = pluginPropertiesService.getPropertyValue(batchInstanceID, SEARCH_CLASSIFICATION_PLUGIN,
					LuceneProperties.LUCENE_VALID_EXTNS);
			String indexFields = pluginPropertiesService.getPropertyValue(batchInstanceID, SEARCH_CLASSIFICATION_PLUGIN,
					LuceneProperties.LUCENE_INDEX_FIELDS);
			String stopWords = pluginPropertiesService.getPropertyValue(batchInstanceID, SEARCH_CLASSIFICATION_PLUGIN,
					LuceneProperties.LUCENE_STOP_WORDS);
			String minTermFreq = pluginPropertiesService.getPropertyValue(batchInstanceID, SEARCH_CLASSIFICATION_PLUGIN,
					LuceneProperties.LUCENE_MIN_TERM_FREQ);
			String minDocFreq = pluginPropertiesService.getPropertyValue(batchInstanceID, SEARCH_CLASSIFICATION_PLUGIN,
					LuceneProperties.LUCENE_MIN_DOC_FREQ);
			String minWordLength = pluginPropertiesService.getPropertyValue(batchInstanceID, SEARCH_CLASSIFICATION_PLUGIN,
					LuceneProperties.LUCENE_MIN_WORD_LENGTH);
			String maxQueryTerms = pluginPropertiesService.getPropertyValue(batchInstanceID, SEARCH_CLASSIFICATION_PLUGIN,
					LuceneProperties.LUCENE_MAX_QUERY_TERMS);
			String topLevelField = pluginPropertiesService.getPropertyValue(batchInstanceID, SEARCH_CLASSIFICATION_PLUGIN,
					LuceneProperties.LUCENE_TOP_LEVEL_FIELD);
			String numOfPages = pluginPropertiesService.getPropertyValue(batchInstanceID, SEARCH_CLASSIFICATION_PLUGIN,
					LuceneProperties.LUCENE_NO_OF_PAGES);
			String max_results = pluginPropertiesService.getPropertyValue(batchInstanceID, SEARCH_CLASSIFICATION_PLUGIN,
					LuceneProperties.LUCENE_MAX_RESULT_COUNT);
			int max_result = 10;
			try {
				max_result = Integer.valueOf(max_results);
			} catch (NumberFormatException e) {
				logger.info("Could not set max result value. Using default value of 10.");
			}
			logger.info("Properties Initialized Successfully");

			Batch batch = batchSchemaService.getBatch(batchInstanceID);
			String batchClassIdentifier = batch.getBatchClassIdentifier();
			String indexFolder = batchSchemaService.getSearchClassIndexFolder(batchClassIdentifier, false);

			String[] validExtensions = validExt.split(";");
			String[] allIndexFields = indexFields.split(";");
			String[] allStopWords = stopWords.split(";");
			if (allIndexFields == null || allIndexFields.length <= 0) {
				logger.error("Cannot read Index level fields from resources");
				throw new DCMAApplicationException("Cannot read Index level fields from resources");
			}
			String actualFolderLocation = batchSchemaService.getLocalFolderLocation() + File.separator + batchInstanceID;
			IndexReader reader = null;
			List<Page> allPages = null;
			Query query = null;
			Map<String, Float> returnMap = new HashMap<String, Float>();

			try {
				allPages = findAllHocrFromXML(batch);
			} catch (DCMAApplicationException e1) {
				logger.error("Exception while reading from XML" + e1.getMessage());
				throw new DCMAApplicationException(e1.getMessage(), e1);
			}
			// List<com.ephesoft.dcma.da.domain.PageType> allPageTypes =
			// pageTypeService.getPageTypesByBatchInstanceID(batchInstanceID);
			List<com.ephesoft.dcma.da.domain.PageType> allPageTypes = pluginPropertiesService.getPageTypes(batchInstanceID);
			if (!(allPageTypes != null && !allPageTypes.isEmpty())) {
				logger.error("Page Types not configured in Database");
				throw new DCMAApplicationException("Page Types not configured in Database");
			}

			String[] indexFiles = new File(indexFolder).list();
			if (indexFiles == null || indexFiles.length <= 0) {
				logger.info("No index files exist inside folder : " + indexFolder);
				throw new DCMAApplicationException("No index files exist inside folder : " + indexFolder);
			}

			try {
				reader = IndexReader.open(FSDirectory.open(new File(indexFolder)), true);
			} catch (CorruptIndexException e) {
				logger.error("CorruptIndexException while reading Index" + e.getMessage(), e);
				throw new DCMAApplicationException("CorruptIndexException while reading Index" + e.getMessage(), e);
			} catch (IOException e) {
				logger.error("IOException while reading Index" + e.getMessage(), e);
				throw new DCMAApplicationException("IOException while reading Index" + e.getMessage(), e);
			}
			MoreLikeThis moreLikeThis = new MoreLikeThis(reader);
			moreLikeThis.setFieldNames(allIndexFields);
			// moreLikeThis.setBoost(true);
			// moreLikeThis.setBoostFactor(10.0f);
			moreLikeThis.setMinTermFreq(Integer.valueOf(minTermFreq));
			moreLikeThis.setMinDocFreq(Integer.valueOf(minDocFreq));
			moreLikeThis.setMinWordLen(Integer.valueOf(minWordLength));
			moreLikeThis.setMaxQueryTerms(Integer.valueOf(maxQueryTerms));
			if (allStopWords != null && allStopWords.length > 0) {
				Set<String> stopWordsTemp = new HashSet<String>();
				for (int i = 0; i < allStopWords.length; i++) {
					stopWordsTemp.add(allStopWords[i]);
				}
				moreLikeThis.setStopWords(stopWordsTemp);
			}

			if (null != allPages) {
				for (Page page : allPages) {
					String eachPage = page.getHocrFileName();

					eachPage = eachPage.trim();
					boolean isFileValid = false;
					if (validExtensions != null && validExtensions.length > 0) {
						for (int l = 0; l < validExtensions.length; l++) {
							if (eachPage.endsWith(validExtensions[l])) {
								isFileValid = true;
								break;
							}
						}
					} else {
						logger.error("No valid extensions are specified in resources");
						cleanUpResource(reader);
						throw new DCMAApplicationException("No valid extensions are specified in resources");
					}
					if (isFileValid) {
						try {
							logger.info("Generating query for : " + eachPage);
							String hocrContent = null;

							String pageID = page.getIdentifier();
							HocrPages hocrPages = batchSchemaService.getHocrPages(batchInstanceID, pageID);
							List<HocrPage> hocrPageList = hocrPages.getHocrPage();
							HocrPage hocrPage = hocrPageList.get(0);
							hocrContent = hocrPage.getHocrContent();

							if (null != hocrContent) {
								try {
									InputStream is = new ByteArrayInputStream(hocrContent.getBytes("UTF-8"));
									query = moreLikeThis.like(is);
								} catch (UnsupportedEncodingException e) {
									logger.error(e.getMessage(), e);
									query = moreLikeThis.like(new File(actualFolderLocation + File.separator + eachPage));
								}
							} else {
								query = moreLikeThis.like(new File(actualFolderLocation + File.separator + eachPage));
							}

							if (query != null && query.toString() != null && query.toString().length() > 0) {
								logger.info("Generating confidence score for : " + eachPage);
								returnMap = SearchFiles.generateConfidence(indexFolder, query.toString(), topLevelField, Integer
										.valueOf(numOfPages), max_result);
							} else {
								logger.info("Empty query generated for : " + eachPage);
							}

						} catch (IOException e) {
							logger.error("Exception while generating query for :" + eachPage + e.getMessage(), e);
							cleanUpResource(reader);
							throw new DCMAApplicationException("Exception while generating query for :" + eachPage + e.getMessage(), e);
						} catch (Exception e) {
							cleanUpResource(reader);
							logger.error("Exception while generating index or updating xml" + e.getMessage(), e);
						}
						logger.info("Started updating batch xml for : " + eachPage);
						updateBatchXML(returnMap, batch, eachPage, allPageTypes);
						logger.info("Successfully ended updating batch xml for : " + eachPage);
					} else {
						logger.error("File " + eachPage + " has invalid extension.");
						throw new DCMABusinessException("File " + eachPage + " has invalid extension.");
					}
				}
			} else {
				logger.error("No pages found in batch XML.");
				throw new DCMAApplicationException("No pages found in batch XML.");
			}
			batchSchemaService.updateBatch(batch);
			logger.info("Batch XML updated.");
			cleanUpResource(reader);
		} else {
			logger.info("Skipping search classification. Switch mode set as OFF.");
		}
	}

	public void cleanUpResource(IndexReader reader) {
		if (reader != null) {
			try {
				reader.close();
			} catch (IOException e) {
				logger.info("There was a problem in closing the Index reader.");
			}
		}
	}

	/**
	 * This method finds the name of all HOCR files from batch xml which are generated by tesseract/ocropus plugin .
	 * 
	 * @param batch Batch
	 * @return List<PageType>
	 * @throws DCMAApplicationException
	 */
	public List<Page> findAllHocrFromXML(final Batch batch) throws DCMAApplicationException {
		List<Page> allPages = new ArrayList<Page>();

		List<Document> xmlDocuments = batch.getDocuments().getDocument();
		for (int i = 0; i < xmlDocuments.size(); i++) {
			Document document = xmlDocuments.get(i);
			List<Page> listOfPages = document.getPages().getPage();
			allPages.addAll(listOfPages);
		}
		return allPages;
	}

	/**
	 * This method updates the batch xml for each HOCR file and stores the confidence and value at page level field. The maximum value
	 * is stored at page level field and values for all the page types including maximum value are stored as alternate values.
	 * 
	 * @param batchDocNameScore Map<String, Float>
	 * @param batch Batch
	 * @param eachHtmlPage String
	 * @param allPageTypes List<com.ephesoft.dcma.da.domain.PageType>
	 * @throws DCMAApplicationException
	 */
	public void updateBatchXML(final Map<String, Float> batchDocNameScore, final Batch batch, final String eachHtmlPage,
			final List<com.ephesoft.dcma.da.domain.PageType> allPageTypes) throws DCMAApplicationException {
		List<Document> xmlDocuments = batch.getDocuments().getDocument();
		float highestConfScore = fetchHighestScoreValue(batchDocNameScore);
		for (int i = 0; i < xmlDocuments.size(); i++) {
			Document document = xmlDocuments.get(i);
			List<Page> listOfPages = document.getPages().getPage();
			for (int j = 0; j < listOfPages.size(); j++) {
				Page page = listOfPages.get(j);
				if (page.getHocrFileName().equalsIgnoreCase(eachHtmlPage)) {
					PageLevelFields pageLevelFields = page.getPageLevelFields();
					if (pageLevelFields == null) {
						pageLevelFields = new PageLevelFields();
					}
					List<DocField> allPageLevelFields = pageLevelFields.getPageLevelField();
					DocField docFieldType = new DocField();
					AlternateValues alternateValues = docFieldType.getAlternateValues();
					if (!allPageTypes.isEmpty()) {
						docFieldType.setName(FIELD_TYPE_NAME);
						docFieldType.setCoordinatesList(null);
						docFieldType.setType("");
						docFieldType.setConfidence(calculateConfidenceScore(highestConfScore));
						docFieldType.setValue(fetchPageValueByConfidence(batchDocNameScore, highestConfScore));
						for (int k = 0; k < allPageTypes.size(); k++) {
							com.ephesoft.dcma.da.domain.PageType eachPageType = allPageTypes.get(k);
							if (eachPageType != null && batchDocNameScore.containsKey(eachPageType.getName())) {
								if (alternateValues == null) {
									alternateValues = new AlternateValues();
								}
								float eachScore = 0f;
								eachScore = batchDocNameScore.get(eachPageType.getName());
								Field fieldType = new Field();
								fieldType.setConfidence(calculateConfidenceScore(eachScore));
								fieldType.setValue(eachPageType.getName());
								fieldType.setName(FIELD_TYPE_NAME);
								fieldType.setType("");
								fieldType.setCoordinatesList(null);
								alternateValues.getAlternateValue().add(fieldType);
							}
						}
						docFieldType.setAlternateValues(alternateValues);
					}
					allPageLevelFields.add(docFieldType);
					page.setPageLevelFields(pageLevelFields);
				}
			}
		}
	}

	/**
	 * This method finds the highest score among all the scores inside batchDocNameScore.
	 * 
	 * @param batchDocNameScore Map<String, Float>
	 * @return float
	 */
	public float fetchHighestScoreValue(final Map<String, Float> batchDocNameScore) {
		float highestValue = 0f;
		Set<String> set = batchDocNameScore.keySet();
		Iterator<String> iterator = set.iterator();
		while (iterator.hasNext()) {
			String learnName = iterator.next();
			float eachValue = batchDocNameScore.get(learnName);
			if (eachValue > highestValue) {
				highestValue = eachValue;
			}
		}
		return highestValue;
	}

	/**
	 * This method calculates the actual confidence score to be updated in batch xml from the score returned by Lucene plugin.
	 * 
	 * @param score float
	 * @param bestScore float
	 * @return int
	 */
	public float calculateConfidenceScore(final float score) {
		float returnScore = 0;
		try {
			returnScore = 100.0f * (1.0f - (1.0f / (1.0f + score)));
			DecimalFormat twoDForm = new DecimalFormat("#0.00");
			returnScore = Float.valueOf(twoDForm.format(returnScore));
		} catch (Exception e) {
			logger.error("Error while converting score" + score + " to Decimal Format", e);
		}
		return returnScore;
	}

	/**
	 * This method extracts the value to be stored at page level field inside batch xml from the confidence score.
	 * 
	 * @param batchDocNameScore Map<String, Float>
	 * @param confidenceScore float
	 * @return String
	 */
	public String fetchPageValueByConfidence(final Map<String, Float> batchDocNameScore, final float confidenceScore) {
		String returnValue = "";
		if (confidenceScore != 0) {
			float learnScore = 0f;
			List<String> sameScoreDocs = new ArrayList<String>();
			Set<String> set = batchDocNameScore.keySet();
			Iterator<String> iterator = set.iterator();
			while (iterator.hasNext()) {
				String learnName = iterator.next();
				learnScore = batchDocNameScore.get(learnName);
				if (learnScore == confidenceScore) {
					sameScoreDocs.add(learnName);
				}
			}
			if (!sameScoreDocs.isEmpty()) {
				if (sameScoreDocs.size() == 1) {
					returnValue = sameScoreDocs.get(0);
				} else if (sameScoreDocs.size() >= 1) {
					int firstIndex = -1;
					int middleIndex = -1;
					int lastIndex = -1;
					for (int i = 0; i < sameScoreDocs.size(); i++) {
						if (sameScoreDocs.get(i).contains(firstPage)) {
							firstIndex = i;
						} else if (sameScoreDocs.get(i).contains(middlePage)) {
							middleIndex = i;
						} else if (sameScoreDocs.get(i).contains(lastPage)) {
							lastIndex = i;
						}
					}
					if (firstIndex != -1) {
						returnValue = sameScoreDocs.get(firstIndex);
					} else if (lastIndex != -1) {
						returnValue = sameScoreDocs.get(lastIndex);
					} else if (middleIndex != -1) {
						returnValue = sameScoreDocs.get(middleIndex);
					}
				}
			}
		}
		return returnValue;
	}

	/**
	 * This method is used to generate index of a well defined set of HOCR files placed in a hierarchy : batch class > document type >
	 * page type.
	 * 
	 * @param batchClassIdentifier String
	 * @param createIndex boolean
	 */
	public void learnSampleHocrFiles(final String batchClassIdentifier, final boolean createIndex) throws DCMAApplicationException {
		String admLearnFolder = batchSchemaService.getSearchClassSamplePath(batchClassIdentifier, false);
		String admIndexFolder = batchSchemaService.getSearchClassIndexFolder(batchClassIdentifier, false);
		if (!(admLearnFolder.length() > 0 && admIndexFolder.length() > 0)) {
			logger.error("Problem in initializing Lucene properties");
			throw new DCMAApplicationException("Problem initializing Lucene properties");
		}
		deleteIndexes(admIndexFolder);
		generateHOCRFiles(admLearnFolder, batchClassIdentifier);
		try {
			IndexHTML.generateIndex(admIndexFolder, admLearnFolder, createIndex);
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new DCMAApplicationException(e.getMessage(), e);
		}
	}

	/**
	 * This method is used to generate index of a well defined set of HOCR files placed in a hierarchy : batch class > document type >
	 * page type.
	 * 
	 * @param batchClassIdentifier String
	 * @param createIndex boolean
	 */
	public void learnSampleHocrFilesForTesseract(final String batchClassIdentifier, final boolean createIndex)
			throws DCMAApplicationException {
		String admLearnFolder = batchSchemaService.getSearchClassSamplePath(batchClassIdentifier, false);
		String admIndexFolder = batchSchemaService.getSearchClassIndexFolder(batchClassIdentifier, false);
		if (!(admLearnFolder.length() > 0 && admIndexFolder.length() > 0)) {
			logger.error("Problem initializing Lucene properties");
			throw new DCMAApplicationException("Problem initializing Lucene properties");
		}
		deleteIndexes(admIndexFolder);
		generateHOCRFilesFromTesseract(admLearnFolder, batchClassIdentifier);
		String batchFolderPath = admLearnFolder;
		try {
			IndexHTML.generateIndex(admIndexFolder, batchFolderPath, createIndex);
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new DCMAApplicationException(e.getMessage(), e);
		}
		cleanUpIntrmediatePngs(batchFolderPath);
	}

	/**
	 * This method cleans up all the intermediate PNG files which are formed by tesseract plugin in process of creation of HOCR files.
	 * 
	 * @param batchFolderPath String
	 */
	public void cleanUpIntrmediatePngs(final String batchFolderPath) {

		if (null == batchFolderPath) {
			return;
		}

		File batchFile = new File(batchFolderPath);
		if (batchFile.isDirectory()) {
			File[] allDocFiles = batchFile.listFiles();
			if (null == allDocFiles) {
				return;
			}

			for (File documentFile : allDocFiles) {
				if (documentFile.isDirectory()) {
					File[] allPageFiles = documentFile.listFiles();
					if (null == allPageFiles) {
						continue;
					}
					for (File pageFile : allPageFiles) {
						if (pageFile.isDirectory()) {
							File[] allFiles = pageFile.listFiles();
							if (null == allFiles) {
								continue;
							}
							for (File file : allFiles) {
								if (file.isDirectory()) {
									continue;
								}
								if (file.getName().toLowerCase().contains(TIF_FILE_EXT)
										&& file.getName().toLowerCase().contains(PNG_FILE_EXT)) {
									file.delete();
								}
							}
						}
					}
				} else {
					if (documentFile.getName().toLowerCase().contains(TIF_FILE_EXT)
							&& documentFile.getName().toLowerCase().contains(PNG_FILE_EXT)) {
						documentFile.delete();
					}
				}
			}

		}
	}

	/**
	 * This method deletes the previous indexes before creating new one.
	 * 
	 * @param admIndexFolder
	 */
	public void deleteIndexes(final String admIndexFolder) {
		FileUtils.deleteContents(admIndexFolder, false);
	}

	/**
	 * This method generates the HOCR file when "Learn Files" button is pressed from Admin UI.
	 * 
	 * @param learnFolder String
	 * @param batchClassIdentifier String
	 * @throws DCMAApplicationException
	 */
	public void generateHOCRFiles(final String learnFolder, final String batchClassIdentifier) throws DCMAApplicationException {
		logger.info("Inside Recostar hocr generation");
		Map<String, String> properties = batchClassPluginConfigService.getPluginPropertiesForBatchClass(batchClassIdentifier,
				RECOSTAR_HOCR_PLUGIN, null);
		String projectFileName = "";
		if (properties != null && !properties.isEmpty()) {
			projectFileName = properties.get(LuceneProperties.RECOSTAR_PROJECT_FILE.getPropertyKey());
		}
		if (projectFileName.length() <= 0) {
			logger.error("Project File Name could not be fetched");
			throw new DCMAApplicationException("Project File Name could not be fetched");
		}

		String workingDirectory = getWorkingDirectory();
		if (workingDirectory == null || workingDirectory.length() == 0) {
			logger.error("Working directory for License file could not be found");
			throw new DCMAApplicationException("Working directory for License file could not be found");
		}
		String jarPath = getJarPath();
		if (jarPath == null || jarPath.length() == 0) {
			logger.error("Jar Path for License file could not be found");
			throw new DCMAApplicationException("Jar Path for License file could not be found");
		}
		BatchInstanceThread batchInstanceThread = new BatchInstanceThread();
		File fBatchClass = new File(learnFolder);
		String[] listOfDocumetFolders = fBatchClass.list();
		if (listOfDocumetFolders != null && listOfDocumetFolders.length > 0) {
			for (String documentFolder : listOfDocumetFolders) {
				File fDocumentFolder = new File(fBatchClass.getAbsolutePath() + File.separator + documentFolder);
				String[] listOfPageFolders = fDocumentFolder.list();
				if (listOfPageFolders != null && listOfPageFolders.length > 0) {
					for (String pageFolderName : listOfPageFolders) {
						String pageFolderPath = fBatchClass.getAbsolutePath() + File.separator + documentFolder + File.separator
								+ pageFolderName;
						File fPageFolder = new File(pageFolderPath);
						String[] listOfHtmlFiles = fPageFolder.list(new CustomFileFilter(false, FileType.HTML.getExtensionWithDot()));
						String[] listOfImageFiles = fPageFolder.list(new CustomFileFilter(false, FileType.TIF.getExtensionWithDot(),
								FileType.TIFF.getExtensionWithDot()));
						if (listOfImageFiles != null && listOfImageFiles.length > 0) {
							for (String eachImageFile : listOfImageFiles) {
								String hocrFileName = eachImageFile.substring(0, eachImageFile.toLowerCase().lastIndexOf(".tif"))
										+ ".html";
								boolean hocrExists = checkHocrExists(hocrFileName, listOfHtmlFiles);
								if (!hocrExists) {
									String imageAbsolutePath = pageFolderPath + File.separator + eachImageFile;
									String targetHTMlAbsolutePath = pageFolderPath + File.separator
											+ eachImageFile.substring(0, eachImageFile.lastIndexOf(".")) + ".html";
									try {
										String[] cmds = new String[3];
										cmds[0] = "cmd";
										cmds[1] = "/c";
										cmds[2] = "RecostarPlugin.exe " + " RSO2-NET.476 " + projectFileName + " \""
												+ imageAbsolutePath + "\" " + workingDirectory + " " + jarPath + " "
												+ LICENSE_VERIFIER + "  \"" + targetHTMlAbsolutePath + "\"";
										logger.info("command formed is :" + cmds[2]);
										batchInstanceThread
												.add(new ProcessExecutor(cmds, new File(System.getenv(RECOSTAR_BASE_PATH))));
										logger.info("Added HOCR file : " + targetHTMlAbsolutePath);
									} catch (Exception e) {
										logger.error("Exception occured while generating HOCR for image" + imageAbsolutePath
												+ e.getMessage());
										throw new DCMAApplicationException("Exception while generating HOCR for image"
												+ imageAbsolutePath + e.getMessage(), e);
									}
								} else {
									logger.info("HOCR already present for image : " + eachImageFile);
								}

							}
						} else {
							logger.info("No Image file found");
						}
					}
				} else {
					logger.info("No Page types present");
				}
			}
		} else {
			logger.info("No Document types present");
		}
		logger.info("Executing commands in multi thread environment");
		batchInstanceThread.execute();
		logger.info("Learning completed successfully");
	}

	/**
	 * This method returns the working directory.
	 * 
	 * @return String
	 * @throws DCMAApplicationException
	 */
	private String getWorkingDirectory() throws DCMAApplicationException {
		String jarPath = getJarPath();
		String workingDirectory = "";
		if (jarPath != null && !jarPath.isEmpty()) {
			if (jarPath.contains(File.separator)) {
				workingDirectory = jarPath.substring(jarPath.indexOf(File.separator) + 1, jarPath.lastIndexOf(File.separator));
			} else {
				workingDirectory = jarPath.substring(jarPath.indexOf('/') + 1, jarPath.lastIndexOf('/'));
			}
		}
		return workingDirectory;
	}

	/**
	 * This method returns the jar path.
	 * 
	 * @return String
	 * @throws DCMAApplicationException
	 */
	private String getJarPath() throws DCMAApplicationException {
		String jarPath = "";
		try {
			jarPath = Class.forName(LICENSE_VERIFIER).newInstance().getClass().getProtectionDomain().getCodeSource().getLocation()
					.getPath();
		} catch (InstantiationException e) {
			logger.error("Problem occured in fetching the jarPath" + e);
			throw new DCMAApplicationException("Problem in fetching the jarPath" + e);
		} catch (IllegalAccessException e) {
			logger.error("Problem has occured in fetching the jarPath" + e);
			throw new DCMAApplicationException("Problem in fetching the jarPath" + e);
		} catch (ClassNotFoundException e) {
			logger.error("Problem occurs in fetching the jarPath" + e);
			throw new DCMAApplicationException("Problem in fetching the jarPath" + e);
		}
		return jarPath;
	}

	private boolean checkHocrExists(final String hocrName, final String[] listOfFiles) {
		boolean returnValue = false;
		if (listOfFiles != null && listOfFiles.length > 0) {
			for (String eachFile : listOfFiles) {
				if (eachFile.equalsIgnoreCase(hocrName)) {
					returnValue = true;
				}
			}
		}
		return returnValue;
	}

	/**
	 * This method generates the hocr files for all the images present inside supplied folder for a paticular batch class.
	 * 
	 * @param imageFolder
	 * @param ocrEngineName
	 * @return path of output file
	 * @throws DCMAApplicationException
	 */
	public List<String> generateHOCRForKVExtractionTest(final String imageFolder, final String ocrEngineName,
			final String batchClassIdentifer) throws DCMAApplicationException {
		logger.info("Inside generateHOCRForKVExtractionTest");

		List<String> targetHtmlPaths = new ArrayList<String>();
		File imageFolderPath = new File(imageFolder);
		File[] listOfimages = imageFolderPath.listFiles(new CustomFileFilter(false, FileType.TIF.getExtensionWithDot(), FileType.TIFF
				.getExtensionWithDot()));
		String[] listOfHtmlFiles = imageFolderPath.list(new CustomFileFilter(false, FileType.HTML.getExtensionWithDot()));
		String targetHTMlAbsolutePath = null;
		if (ocrEngineName == null || ocrEngineName.isEmpty()) {
			logger.info("OCR Engine not configured.");
			return null;
		}

		String tesseractBasePath = "";
		String tesseractVersion = "";
		if (ocrEngineName.equalsIgnoreCase("TESSERACT_HOCR")) {
			BatchPluginConfiguration[] pluginConfiguration = batchClassPluginPropertiesService.getPluginProperties(
					batchClassIdentifer, TESSERACT_HOCR_PLUGIN, TesseractVersionProperty.TESSERACT_VERSIONS);

			if (pluginConfiguration != null && pluginConfiguration.length > 0 && pluginConfiguration[0].getValue() != null
					&& pluginConfiguration[0].getValue().length() > 0) {
				tesseractVersion = pluginConfiguration[0].getValue();
			}
			try {
				ApplicationConfigProperties app = new ApplicationConfigProperties();
				tesseractBasePath = app.getProperty(tesseractVersion);
			} catch (IOException ioe) {
				logger.error("Tesseract Base path not configured." + ioe, ioe);
				throw new DCMAApplicationException("Tesseract Base path not configured.", ioe);
			}
			if (tesseractBasePath == null) {
				logger.error("Tesseract Base path not configured.");
				throw new DCMAApplicationException("Tesseract Base path not configured.");
			}
		}

		boolean isTesseractVersion3Batch = false;
		if (listOfimages != null && listOfimages.length > 0) {
			for (File eachImage : listOfimages) {
				if (eachImage.exists()) {
					String imageAbsolutePath = eachImage.getAbsolutePath();
					targetHTMlAbsolutePath = imageAbsolutePath.substring(0, imageAbsolutePath.toLowerCase().indexOf(
							FileType.TIF.getExtensionWithDot()));
					String hocrFileName = eachImage.getName().substring(0, eachImage.getName().toLowerCase().indexOf(".tif"))
							+ ".html";
					boolean hocrExists = checkHocrExists(hocrFileName, listOfHtmlFiles);
					if (!hocrExists) {
						try {
							Runtime runtime = Runtime.getRuntime();
							ArrayList<String> cmdList = new ArrayList<String>();

							if (ocrEngineName.equalsIgnoreCase("TESSERACT_HOCR")) {
								logger.info("Generating hocr from tesseract....");
								if (tesseractVersion.equalsIgnoreCase(TesseractVersionProperty.TESSERACT_VERSION_3.getPropertyKey())) {
									isTesseractVersion3Batch = true;
									logger.info("tesseract version 3....");
									if (OSUtil.isWindows()) {
										cmdList.add("cmd");
										cmdList.add("/c");
										cmdList.add("TesseractConsole.exe \"" + imageAbsolutePath + "\" \"" + targetHTMlAbsolutePath
												+ "\" -l " + " eng +hocr.txt");
									} else {
										// cmdList.add("tesseract " + imageAbsolutePath + " " + targetHTMlAbsolutePath + " -l "
										// + " eng +hocr.txt");

										cmdList.add("tesseract");
										cmdList.add(imageAbsolutePath);
										cmdList.add(targetHTMlAbsolutePath);
										cmdList.add("-l");
										cmdList.add("eng");
										cmdList.add("+hocr.txt");
									}
								} else {
									logger.info("tesseract version 2....");
									targetHTMlAbsolutePath += ".html";
									cmdList.add("cmd");
									cmdList.add("/c");
									cmdList.add("TesseractConsole.exe \"" + imageAbsolutePath + "\" eng " + " > \""
											+ targetHTMlAbsolutePath + "\"");
								}
							} else {
								logger.info("Generating hocr from recostar....");
								targetHTMlAbsolutePath += ".html";
								Map<String, String> properties = batchClassPluginConfigService.getPluginPropertiesForBatchClass(
										batchClassIdentifer, RECOSTAR_HOCR_PLUGIN, null);
								String projectFileName = "";
								if (properties != null && !properties.isEmpty()) {
									projectFileName = properties.get(LuceneProperties.RECOSTAR_PROJECT_FILE.getPropertyKey());
								}
								if (projectFileName.length() <= 0) {
									logger.error("Project File Name could not be fetched");
									throw new DCMAApplicationException("Project File Name could not be fetched");
								}
								String workingDirectory = getWorkingDirectory();
								if (workingDirectory == null || workingDirectory.length() == 0) {
									logger.error("Working directory for License file could not be found");
									throw new DCMAApplicationException("Working directory for License file could not be found");
								}
								String jarPath = getJarPath();
								if (jarPath == null || jarPath.length() == 0) {
									logger.error("Jar Path for License file could not be found");
									throw new DCMAApplicationException("Jar Path for License file could not be found");
								}
								cmdList.add("cmd");
								cmdList.add("/c");
								cmdList.add("RecostarPlugin.exe " + " RSO2-NET.476 " + projectFileName + " \"" + imageAbsolutePath
										+ "\" " + workingDirectory + " " + jarPath + " " + LICENSE_VERIFIER + "  \""
										+ targetHTMlAbsolutePath + "\"");
							}
							File envFile = null;

							if (ocrEngineName.equalsIgnoreCase("TESSERACT_HOCR")) {
								envFile = new File(tesseractBasePath);
							} else {
								envFile = new File(System.getenv(RECOSTAR_BASE_PATH));
							}
							String[] cmds = cmdList.toArray(new String[cmdList.size()]);
							StringBuffer commandStr = new StringBuffer();
							for (int ind = 0; ind < cmds.length; ind++) {
								if (cmds[ind] == null) {
									cmds[ind] = "";
								}
								commandStr.append(cmds[ind] + " ");
							}
							Process process = runtime.exec(cmds, null, envFile);
							int exitValue = process.waitFor();
							BufferedReader input = new BufferedReader(new InputStreamReader(process.getErrorStream()));
							String line = null;
							do {
								line = input.readLine();
								logger.debug(line);
							} while (line != null);
							logger.info("Command exited with error code no " + exitValue);
							logger.info("Generated HOCR file : " + targetHTMlAbsolutePath);
						} catch (Exception e) {
							logger.error("Exception while generating HOCR for image" + imageAbsolutePath + e.getMessage());
							throw new DCMAApplicationException("Exception while generating HOCR for image" + imageAbsolutePath
									+ e.getMessage(), e);
						}

					}
					if (isTesseractVersion3Batch || hocrExists) {
						targetHTMlAbsolutePath += ".html";
					}
					targetHtmlPaths.add(targetHTMlAbsolutePath);
				}
			}
		} else {
			logger.info("No images found for learning inside :" + imageFolder);
		}

		return targetHtmlPaths;
	}

	/**
	 * This method generates the HOCR file when "Learn Files" button is pressed from Admin UI.
	 * 
	 * @param learnFolder String
	 * @param batchClass String
	 * @throws DCMAApplicationException
	 */
	public void generateHOCRFilesFromTesseract(final String learnFolder, final String batchClass) throws DCMAApplicationException {
		logger.info("Inside Tesseract hocr generation");

		BatchPluginConfiguration[] pluginConfiguration = batchClassPluginPropertiesService.getPluginProperties(batchClass,
				TESSERACT_HOCR_PLUGIN, TesseractVersionProperty.TESSERACT_VERSIONS);
		String tesseractVersion = "";
		if (pluginConfiguration != null && pluginConfiguration.length > 0 && pluginConfiguration[0].getValue() != null
				&& pluginConfiguration[0].getValue().length() > 0) {
			tesseractVersion = pluginConfiguration[0].getValue();
		}
		String tesseractBasePath = "";
		try {
			ApplicationConfigProperties app = new ApplicationConfigProperties();
			tesseractBasePath = app.getProperty(tesseractVersion);
		} catch (IOException ioe) {
			logger.error("Tesseract Base path not configured." + ioe, ioe);
			throw new DCMAApplicationException("Tesseract Base path not configured.", ioe);
		}
		if (tesseractBasePath == null) {
			logger.error("Tesseract Base path not configured.");
			throw new DCMAApplicationException("Tesseract Base path not configured.");
		}

		File fBatchClass = new File(learnFolder);
		String[] listOfDocumetFolders = fBatchClass.list();
		BatchInstanceThread batchInstanceThread = new BatchInstanceThread();
		if (listOfDocumetFolders != null && listOfDocumetFolders.length > 0) {
			for (String documentFolder : listOfDocumetFolders) {
				File fDocumentFolder = new File(fBatchClass.getAbsolutePath() + File.separator + documentFolder);
				String[] listOfPageFolders = fDocumentFolder.list();
				if (listOfPageFolders != null && listOfPageFolders.length > 0) {
					for (String pageFolderName : listOfPageFolders) {
						String pageFolderPath = fBatchClass.getAbsolutePath() + File.separator + documentFolder + File.separator
								+ pageFolderName;
						File fPageFolder = new File(pageFolderPath);
						String[] listOfHtmlFiles = fPageFolder.list(new CustomFileFilter(false, FileType.HTML.getExtensionWithDot()));
						String[] listOfImageFiles = fPageFolder.list(new CustomFileFilter(false, FileType.TIF.getExtensionWithDot(),
								FileType.TIFF.getExtensionWithDot()));
						if (listOfImageFiles != null && listOfImageFiles.length > 0) {
							for (String eachImageFile : listOfImageFiles) {
								String hocrFileName = eachImageFile.substring(0, eachImageFile.toLowerCase().indexOf(".tif"))
										+ ".html";
								boolean hocrExists = checkHocrExists(hocrFileName, listOfHtmlFiles);
								if (!hocrExists) {
									String imageAbsolutePath = pageFolderPath + File.separator + eachImageFile;

									String targetHTMlAbsolutePath = "";
									if (tesseractVersion.equalsIgnoreCase(TesseractVersionProperty.TESSERACT_VERSION_3
											.getPropertyKey())) {
										targetHTMlAbsolutePath = pageFolderPath + File.separator
												+ eachImageFile.substring(0, eachImageFile.indexOf("."));
									} else {
										targetHTMlAbsolutePath = pageFolderPath + File.separator
												+ eachImageFile.substring(0, eachImageFile.indexOf(".")) + ".html";
									}
									try {
										// Runtime runtime = Runtime.getRuntime();
										String[] cmds = new String[6];
										if (tesseractVersion.equalsIgnoreCase(TesseractVersionProperty.TESSERACT_VERSION_3
												.getPropertyKey())) {
											if (OSUtil.isWindows()) {
												cmds[0] = "cmd";
												cmds[1] = "/c";
												cmds[2] = "TesseractConsole.exe \"" + imageAbsolutePath + "\" \""
														+ targetHTMlAbsolutePath + "\" -l " + " eng +hocr.txt";
											} else {
												// cmds[0] = "tesseract " + imageAbsolutePath + " " + targetHTMlAbsolutePath + " -l "
												// + " eng +hocr.txt";
												cmds[0] = "tesseract";
												cmds[1] = imageAbsolutePath;
												cmds[2] = targetHTMlAbsolutePath;
												cmds[3] = "-l";
												cmds[4] = "eng";
												cmds[5] = "+hocr.txt";
											}
										} else {
											cmds[0] = "cmd";
											cmds[1] = "/c";
											cmds[2] = "TesseractConsole.exe \"" + imageAbsolutePath + "\" eng " + " > \""
													+ targetHTMlAbsolutePath + "\"";
										}
										batchInstanceThread.add(new ProcessExecutor(cmds, new File(tesseractBasePath)));
										logger.info("Added HOCR file : " + targetHTMlAbsolutePath);
									} catch (Exception e) {
										logger.error("Exception while generating HOCR for image" + imageAbsolutePath + e.getMessage());
										throw new DCMAApplicationException("Exception while generating HOCR for image"
												+ imageAbsolutePath + e.getMessage(), e);
									}
								} else {
									logger.info("HOCR already present for image : " + eachImageFile);
								}

							}
						} else {
							logger.info("No Image file found");
						}
					}
				} else {
					logger.info("No Page types present");
				}
			}
		} else {
			logger.info("No Document types present");
		}
		logger.info("Executing commands in multi thread environment");
		batchInstanceThread.execute();
		logger.info("Learning completed successfully");
	}

}