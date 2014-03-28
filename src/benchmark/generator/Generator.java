/*
 * Copyright (C) 2008 Andreas Schultz
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package benchmark.generator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.Vector;
import java.util.Map.Entry;

import benchmark.model.BSBMResource;
import benchmark.model.Offer;
import benchmark.model.Person;
import benchmark.model.Producer;
import benchmark.model.Product;
import benchmark.model.ProductFeature;
import benchmark.model.ProductType;
import benchmark.model.RatingSite;
import benchmark.model.Review;
import benchmark.model.Vendor;
import benchmark.serializer.MonetDBSerializer;
import benchmark.serializer.NTriples;
import benchmark.serializer.NTriplesGZ;
import benchmark.serializer.ObjectBundle;
import benchmark.serializer.SQLSerializer;
import benchmark.serializer.Serializer;
import benchmark.serializer.TriG;
import benchmark.serializer.Turtle;
import benchmark.serializer.TurtleGZ;
import benchmark.serializer.VirtSerializer;
import benchmark.serializer.XMLSerializer;
import benchmark.tools.NGram;
import benchmark.vocabulary.BSBM;
import benchmark.vocabulary.ISO3166;

public class Generator {
	
	//Program parameters and default values
	private static int productCount = 100;
	private static boolean forwardChaining = false;
	private static String outputDirectory = "td_data";
	private static String outputFileName = "dataset";
	private static String serializerType = "nt"; 
	private static int nrOfOutputFiles = 1;
	static boolean PARETO = false;
	
	//Update dataset parameters. Output type is always N-Triple.
	private static boolean generateUpdateDataset = false;
	private static String updateDatasetFileName = "dataset_update";
	private static String updateDatasetTransactionSeparator = "\n#__SEP__\n";
	private static int nrOfTransactionsInUpdateDataset = 1000;
	private static int nrOfProductsPerTransaction = 1;
	private static int nrOfMinProductNrForUpdate = Integer.MAX_VALUE;
	private static Serializer updateDatasetSerializer = null;
	private static List<List<BSBMResource>> updateResourceData = null;
	
	//Ratios of different Resources
	static final int productsVendorsRatio = 100;
	
	static final int avgReviewsPerProduct=10;
	static final int avgReviewsPerPerson=20;
	static final int avgReviewsPerRatingSite=10000;
	static final int avgProductsPerProducer = 50;
	static final int avgOffersPerProduct = 20;
	static final int avgOffersPerVendor = productsVendorsRatio * avgOffersPerProduct;
	
	static final String dictionary1File = "titlewords.txt";
	static final String dictionary2File = "2gram.txt";
	static final String dictionary3File = "givennames.txt";
	
	static final Random seedGenerator = new Random(53223436L);
	
	static TextGenerator dictionary1;
	static TextGenerator dictionary2;
	static TextGenerator dictionary3;
	static ReviewTermsGenerator reviewDict;
	
	static GregorianCalendar today = new GregorianCalendar(2008,5,20);//Date of 2008-06-20
	
	static long offerCount;
	static long reviewCount;
	static int productTypeCount;
	static List<Integer> maxProductTypeNrPerLevel;
	
	static boolean namedGraph;

	private static ArrayList<ProductType> productTypeLeaves;
	private static ArrayList<ProductType> productTypeNodes;
	public static ArrayList<Integer> producerOfProduct;//saves producer-product relationship
	public static ArrayList<Long> vendorOfOffer;//saves vendor-offer relationship
	public static ArrayList<Long> ratingsiteOfReview;//saves review-ratingSite relationship
	private static HashMap<String,Integer> wordList;//Word list for the Test driver
	
	private static HashMap<String,Integer> anyProperty1gramList = new HashMap<String, Integer>(); //Word list for the Test driver
	private static HashMap<String,Integer> anyProperty2gramList = new HashMap<String, Integer>(); //Word list for the Test driver
	
	private static Serializer serializer;
	
	private static File outputDir;

	private static StringBuilder sb = new StringBuilder();
	private static ParetoDistGenerator paretoLiteralLength;
	
	
	// Parameter for controlling the datafiles generation
	public static int fromFileId = -1;
	public static int toFileId = 50000; // will not generate more than 50000 files 
	
	public static int noOfMachines = -1;
	public static int thisMachineId = -1;
	
	//Set parameters
	public static void init()
	{
		if(generateUpdateDataset) {
			if(nrOfProductsPerTransaction*nrOfTransactionsInUpdateDataset > productCount) {
				System.err.println("Product count not high enough to generate an update dataset of " + (nrOfProductsPerTransaction*nrOfTransactionsInUpdateDataset) + " products");
				System.exit(-1);
			}
			nrOfMinProductNrForUpdate = productCount - nrOfProductsPerTransaction*nrOfTransactionsInUpdateDataset + 1;
			updateDatasetSerializer = new NTriples(updateDatasetFileName, forwardChaining);
			updateResourceData = new ArrayList<List<BSBMResource>>();
			for(int i = 0; i<nrOfProductsPerTransaction*nrOfTransactionsInUpdateDataset; i++)
				updateResourceData.add(new ArrayList<BSBMResource>());
		}
		offerCount = (long)productCount * avgOffersPerProduct;
		
		//System.out.println("OfferCount = " + offerCount);

		reviewCount = (long)avgReviewsPerProduct * productCount;
		
		producerOfProduct = new ArrayList<Integer>();
		producerOfProduct.add(0);
		vendorOfOffer = new ArrayList<Long>();
		vendorOfOffer.add((long) 0);
		ratingsiteOfReview = new ArrayList<Long>();
		ratingsiteOfReview.add((long)0);
		
		serializer = getSerializer(serializerType);
		if(serializer==null) {
			System.err.println("Invalid Serializer chosen.");
			System.exit(-1);
		}

		namedGraph = isNamedGraphSerializer();
		
		outputDir = new File(outputDirectory);
		outputDir.mkdirs();

		wordList = new HashMap<String, Integer>();
		
		// by default, the dictionary1 is an unigram dictionary
		dictionary1 = new TextGenerator(dictionary1File, seedGenerator.nextLong(), NGram.GRAM1);
		// by default, the dictionary2 is a bigram dictionary
		dictionary2 = new TextGenerator(dictionary2File, seedGenerator.nextLong(), NGram.GRAM2);
		dictionary3 = new TextGenerator(dictionary3File, seedGenerator.nextLong());
		reviewDict = new ReviewTermsGenerator();
		paretoLiteralLength = new ParetoDistGenerator(2.58f, seedGenerator.nextLong());
		System.out.println("");
	}
	
	private static boolean isNamedGraphSerializer() {
		if(serializer instanceof TriG)
			return true;
		else
			return false;
	}
	
	private static Serializer getSerializer(String type) {
		String t = type.toLowerCase();
		if(t.equals("nt"))
			return new NTriples(outputFileName, forwardChaining, nrOfOutputFiles);
		else if(t.equals("ntgz"))
			return new NTriplesGZ(outputFileName, forwardChaining, nrOfOutputFiles);
		else if(t.equals("trig"))
			return new TriG(outputFileName + ".trig", forwardChaining);
		else if(t.equals("ttl"))
			return new Turtle(outputFileName, forwardChaining, nrOfOutputFiles);
		else if(t.equals("ttlgz"))
			return new TurtleGZ(outputFileName, forwardChaining, nrOfOutputFiles);
		else if(t.equals("xml"))
			return new XMLSerializer(outputFileName + ".xml", forwardChaining);
		else if(t.equals("sql"))
			return new SQLSerializer(outputFileName, forwardChaining, "benchmark");
		else if(t.equals("virt"))
			return new VirtSerializer(outputFileName, forwardChaining);
		else if(t.equals("monetdb"))
			return new MonetDBSerializer(outputFileName, forwardChaining, "benchmark");
		else
			return null;
	}
	
	/*
	 * Write data for the Test Driver to disk
	 */
	public static void writeTestDriverData() {
		//Product Type hierarchy to File outputDir/pth.dat
		File pth = new File(outputDir, "pth.dat");
		ObjectOutputStream productTypeOutput;
		try {
			pth.createNewFile();
			productTypeOutput = new ObjectOutputStream(new FileOutputStream(pth, false));
			productTypeOutput.writeObject(productTypeLeaves.toArray(new ProductType[0]));
			productTypeOutput.writeInt(productTypeCount);
			productTypeOutput.writeObject(maxProductTypeNrPerLevel);
		} catch(IOException e) {
			System.err.println("Could not open or create file " + pth.getAbsolutePath());
			System.err.println(e.getMessage());
			System.exit(-1);
		}
		
	
		//Product-Producer Relationships in outputDir/pp.dat
		File pp = new File(outputDir, "pp.dat");
		ObjectOutputStream productProducerOutput;
		try {
			pp.createNewFile();
			productProducerOutput = new ObjectOutputStream(new FileOutputStream(pp, false));
			productProducerOutput.writeObject(producerOfProduct.toArray(new Integer[0]));
		} catch(IOException e) {
			System.err.println("Could not open or create file " + pp.getAbsolutePath());
			System.err.println(e.getMessage());
			System.exit(-1);
		}
		
		//Product-Producer Relationships in outputDir/pp.dat
		File vo = new File(outputDir, "vo.dat");
		ObjectOutputStream offerVendorOutput;
		try {
			vo.createNewFile();
			offerVendorOutput = new ObjectOutputStream(new FileOutputStream(vo, false));
			offerVendorOutput.writeObject(vendorOfOffer.toArray(new Long[0]));
		} catch(IOException e) {
			System.err.println("Could not open or create file " + vo.getAbsolutePath());
			System.err.println(e.getMessage());
			System.exit(-1);
		}
	
		//Review-Rating Site Relationships in outputDir/rr.dat
		File rr = new File(outputDir, "rr.dat");
		ObjectOutputStream reviewRatingsiteOutput;
		try {
			rr.createNewFile();
			reviewRatingsiteOutput = new ObjectOutputStream(new FileOutputStream(rr, false));
			reviewRatingsiteOutput.writeObject(ratingsiteOfReview.toArray(new Long[0]));
		} catch(IOException e) {
			System.err.println("Could not open or create file " + rr.getAbsolutePath());
			System.err.println(e.getMessage());
			System.exit(-1);
		}
		
		//Current date scaling data and words of Product labels in outputDir/cdlw.dat
		File cdlw = new File(outputDir, "cdlw.dat");
		ObjectOutputStream currentDateAndLabelWordsOutput;
		try {
			cdlw.createNewFile();
			currentDateAndLabelWordsOutput = new ObjectOutputStream(new FileOutputStream(cdlw, false));
			currentDateAndLabelWordsOutput.writeInt(productCount);
			currentDateAndLabelWordsOutput.writeLong(reviewCount);
			currentDateAndLabelWordsOutput.writeLong(offerCount);
			currentDateAndLabelWordsOutput.writeObject(today);
			currentDateAndLabelWordsOutput.writeObject(wordList);
		} catch(IOException e) {
			System.err.println("Could not open or create file " + cdlw.getAbsolutePath());
			System.err.println(e.getMessage());
			System.exit(-1);
		}
    
		// Any Property lists in outputDir/anylw.dat
		if (PARETO) {
	    final File anylw = new File(outputDir, "anylw.dat");
	    final ObjectOutputStream anyPropertyWordsOutput;
	    try {
	      anylw.createNewFile();
	      anyPropertyWordsOutput = new ObjectOutputStream(new FileOutputStream(anylw, false));
	      // Creates the frequency ranges
	      for (ArrayList<String> sel : getSelectivityDictionaries(anyProperty1gramList, 0.9f, 0.05f, 0.05f))
	        anyPropertyWordsOutput.writeObject(sel.toArray(new String[0]));
	      for (ArrayList<String> sel : getSelectivityDictionaries(anyProperty2gramList, 0.9f, 0.05f, 0.05f))
	        anyPropertyWordsOutput.writeObject(sel.toArray(new String[0]));
	      anyPropertyWordsOutput.close();
	    } catch(IOException e) {
	      System.err.println("Could not open or create file " + anylw.getAbsolutePath());
	      System.err.println(e.getMessage());
	      System.exit(-1);
	    }		  
		}
	}
	
	/**
	 * Create the Selectivity ranges (i.e., HIGH, LOW, MEDIUM) for the given words list
	 * and with the given distribution.
	 * @param lw the words list
	 * @param high the percentage of the total frequency that will fall in the HIGH range
	 * @param medium the percentage of the total frequency that will fall in the MEDIUM range
	 * @param low the percentage of the total frequency that will fall in the LOW range
	 * @return
	 */
    private static ArrayList<String>[] getSelectivityDictionaries(final Map<String, Integer> lw,
	                                                              final float high, final float medium, final float low) {
	  if (high + medium + low != 1)
	      throw new IllegalArgumentException("the selectivity percentages must sum up to 1");

	  /*
	   * Frequency Ranges creation:
	   * - sort the terms by descending frequency
	   * - compute the boundaries of each range
	   * - put the terms into a range as long as the cumulative frequency is lower than the range boundary
	   */
	  
      @SuppressWarnings("unchecked")
	  final ArrayList<String>[] sel = new ArrayList[3];
	  // Sort the terms by descending order of the frequency
	  final TreeMap<String, Integer> sort = new TreeMap<String, Integer>(new Comparator<String>() {

      @Override
      public int compare(String o1, String o2) {
        return - lw.get(o1).compareTo(lw.get(o2));
      }
      
	  });
	  long totalFreq = 0;
	  
	  sort.putAll(lw);
	  for (Entry<String, Integer> s : sort.entrySet()) {
	    totalFreq += s.getValue();
	  }
	  final Iterator<Entry<String, Integer>> it = sort.entrySet().iterator();
	  // HIGH selectivity
	  sel[0] = getSelectivity(it, high * totalFreq);
	  // MEDIUM selectivity
	  sel[1] = getSelectivity(it, medium * totalFreq);
	  // LOW selectivity
	  sel[2] = getSelectivity(it, Float.MAX_VALUE);
	  return sel;
	}
	
	/**
	 * Create the Selectivity range
	 * @param it The words to add in the range
	 * @param bound the maximum cumulative frequency of the range
	 * @return
	 */
	private static ArrayList<String> getSelectivity(final Iterator<Entry<String, Integer>> it, final float bound) {
	  final ArrayList<String> sel = new ArrayList<String>();
	  long cumulativeFrequency = 0;
	  
	  while (it.hasNext()) {
	    Entry<String, Integer> frq = it.next();
	    cumulativeFrequency += frq.getValue();
	    if (cumulativeFrequency >= bound) {
	      sel.add(frq.getKey());
	      break;
	    }
	    sel.add(frq.getKey());
	  }
	  return sel;
	}
	
	/*
	 * Calculate the number of levels and the branching factors
	 * for the Product Type Hierarchy
	 */
	private static int[] calcBranchingFactors(long scalefactor)
	{
		float logSF = (float)Math.log10(scalefactor);

		//depth = log10(scale factor)/2 + 1
		int depth = Math.round((logSF) / 2) + 1;
		
		int[] branchingFactors = new int[depth];
		
		branchingFactors[0] = 2 * Math.round(logSF);

		int[] temp = { 2, 4, 8};
		for(int i=1;i<depth;i++)
		{
			if(i+1 < depth)
				branchingFactors[i] = 8;
			else {
				int value = temp[Math.round(logSF*3/2+1)%3];
				branchingFactors[i] = value;
			}
		}
		return branchingFactors;
	}
	
	public static Long[] generateSeedsProductType() {
		Long[] seeds = new Long[2];
		for(int i=0;i<seeds.length;i++)
			seeds[i] = seedGenerator.nextLong();
		
		return seeds;
	}
	
	private static String getParetoSentence(final int from, final int to, final ValueGenerator gen) {
	  final int nWords = paretoLiteralLength.getValue(from, to);
	  int len = 0;
	  
	  if (nWords == 1)
	    return dictionary1.getParetoWord();
	  
	  sb.setLength(0);
    if (gen.randomDouble(0, 1) < 0.5) {
      len++;
      sb.append(dictionary1.getParetoWord());
    } else {
      len += 2;
      sb.append(dictionary2.getParetoWord());
    }
	  while (len < nWords) {
	    if (gen.randomDouble(0, 1) < 0.5) {
	      len++;
	      sb.append(' ').append(dictionary1.getParetoWord());
	    } else {
	      len += 2;
	      sb.append(' ').append(dictionary2.getParetoWord());
	    }
	  }
    return sb.toString();
	}
	
	/*
	 * Creates the Product Types and orders them as a tree.
	 */
	public static void createProductTypeHierarchy(Long[] seeds)
	{
		System.out.println("Generating Product Type Hierarchy...");
		DateGenerator publishDateGen = new DateGenerator(new GregorianCalendar(2000,05,20),new GregorianCalendar(2000,06,23),seeds[0]);
		ValueGenerator valueGen = new ValueGenerator(seeds[1]);
		
		ObjectBundle bundle = new ObjectBundle(serializer);
		
		//Calculate branch factors for inner nodes
		int[] branchFt;
		if(productCount>=10)
			branchFt = calcBranchingFactors(productCount);
		else
			branchFt = calcBranchingFactors(10);

		dictionary1.activateLogging(anyProperty1gramList);
		dictionary2.activateLogging(anyProperty2gramList);
		
		productTypeLeaves = new ArrayList<ProductType>();
		productTypeNodes = new ArrayList<ProductType>();
		maxProductTypeNrPerLevel = new ArrayList<Integer>();
		
		LinkedList<ProductType> typeQueue = new LinkedList<ProductType>();
		ProductType root = new ProductType(1,"Thing", "The Product Type of all Products", null);

		if(!namedGraph) {
			root.setPublisher(1);
			root.setPublishDate(publishDateGen.randomDateInMillis());
			String publisher = BSBM.getStandardizationInstitution(1);
			bundle.setPublisher(publisher);
			bundle.setPublisherNum(1);
		}
		else {
			String publisher = BSBM.getStandardizationInstitution(1);
			bundle.setPublisher("<" + publisher + ">");
			bundle.setPublishDate(publishDateGen.randomDateInMillis());
			bundle.setGraphName("<" + publisher + "/Graph-" + DateGenerator.formatDate(bundle.getPublishDate()) + ">");
		}
		productTypeNodes.add(root);
		
		typeQueue.offer(root);
		bundle.add(root);
		
		int oldDepth = -1;
		int nr = 1;
		while(!typeQueue.isEmpty())
		{
			ProductType ptype = typeQueue.poll();
			
			int depth = ptype.getDepth();
			if(oldDepth!=depth) {
				oldDepth = depth;
				maxProductTypeNrPerLevel.add(nr);
			}
		
			for(int i=0;i<branchFt[ptype.getDepth()];i++)
			{
				nr++;
				
				final String label;
				final String comment;
	      if (PARETO) {
	        label = getParetoSentence(1, 3, valueGen);
	        comment = getParetoSentence(1, 1000, valueGen);
	      } else {
	        int labelNrWords = valueGen.randomInt(1, 3);
	        label = dictionary1.getRandomSentence(labelNrWords);
	        
	        int commentNrWords = valueGen.randomInt(20, 50);
	        comment = dictionary2.getRandomSentence(commentNrWords);	        
	      }
				
				ProductType newType = new ProductType(nr, label, comment, ptype);
				
				if(!namedGraph) {
					newType.setPublisher(1);
					newType.setPublishDate(publishDateGen.randomDateInMillis());
				}
				
				bundle.add(newType);
				
				//If new Product Type is leaf
				if(depth==branchFt.length-1) {
					productTypeLeaves.add(newType);
				} else {
					productTypeNodes.add(newType);
					typeQueue.offer(newType);
				}
			}
		}
		if(nr!=maxProductTypeNrPerLevel.get(maxProductTypeNrPerLevel.size()-1))
			maxProductTypeNrPerLevel.add(nr);
		bundle.commitToSerializer();
		System.out.println("Product Type Hierarchy of depth " + branchFt.length + " with " + nr + " Product Types generated.\n");
		productTypeCount = nr;
		
		dictionary1.deactivateLogging();
		dictionary2.deactivateLogging();
	}
	
	public static Long[] generateSeedsProductFeature() {
		Long[] seeds = new Long[2];
		for(int i=0;i<seeds.length;i++)
			seeds[i] = seedGenerator.nextLong();
		
		return seeds;
	}
	
	/*
	 * Create Product Features
	 */
	public static void createProductFeatures(Long[] seeds)
	{
		System.out.println("Generating Product Features...");
		ObjectBundle bundle = new ObjectBundle(serializer);
		ValueGenerator valueGen = new ValueGenerator(seeds[0]);
		DateGenerator publishDateGen = new DateGenerator(new GregorianCalendar(2000,05,20),new GregorianCalendar(2000,06,23),seeds[1]);
		
		dictionary1.activateLogging(anyProperty1gramList);
		dictionary2.activateLogging(anyProperty2gramList);
		
		//Compute count range of Features per Product Type for every depth
		int depth = productTypeLeaves.get(0).getDepth();

		int[] featureFrom = new int[depth];
		int[] featureTo = new int[depth];
		
		//Depth 1 always 5
		featureFrom[0] = 5;
		featureTo[0] = 5;
		
		// -1 because of depth 1
		int depthSum = depth * (depth+1) / 2 - 1;
		
		for(int i=2; i<=depth; i++)
		{
			featureFrom[i-1] = 35 * i / depthSum;
			featureTo[i-1] = 75 * i / depthSum;
		}

		//Product Feature Nr.
		int productFeatureNr = 1;
		
		//First the Inner nodes
		Iterator<ProductType> it = productTypeNodes.iterator();
		it.next();//Ignore the root
		
		if(namedGraph) {
			String publisher = BSBM.getStandardizationInstitution(2);
			bundle.setPublisher("<" + publisher + ">");
			bundle.setPublishDate(publishDateGen.randomDateInMillis());
			bundle.setGraphName("<" + publisher + "/Graph-" + DateGenerator.formatDate(bundle.getPublishDate()) + ">");
		}
		else {
			String publisher = BSBM.getStandardizationInstitution(2);
			bundle.setPublisher(publisher);
			bundle.setPublisherNum(2);
		}
			
		
		while(it.hasNext())
		{
			ProductType pt = it.next();
			int from = featureFrom[pt.getDepth()];
			int to = featureTo[pt.getDepth()];
			
			int count = valueGen.randomInt(from, to);
			Vector<Integer> features = new Vector<Integer>(count);
	      
			for(int i=0;i<count;i++){
			  final String label;
			  final String comment;
			  if (PARETO) {
			    label = getParetoSentence(1, 3, valueGen);
			    comment = getParetoSentence(1, 1000, valueGen);
			  } else {
	        int labelNrWords = valueGen.randomInt(1, 3);
	        label = dictionary1.getRandomSentence(labelNrWords);
	        
	        int commentNrWords = valueGen.randomInt(20, 50);
	        comment = dictionary2.getRandomSentence(commentNrWords);			    
			  }
				
				ProductFeature pf = new ProductFeature(productFeatureNr,label,comment);
				if(!namedGraph) {
					pf.setPublisher(1);
					pf.setPublishDate(publishDateGen.randomDateInMillis());
				}
				
				features.add(productFeatureNr);
				
				bundle.add(pf);
				
				productFeatureNr++;
			}
			pt.setFeatures(features);
		}
		
		//Now the Leaves
		it = productTypeLeaves.iterator();
		
		while(it.hasNext())
		{
			ProductType pt = it.next();
			int from = featureFrom[pt.getDepth()-1];
			int to = featureTo[pt.getDepth()-1];

			int count = valueGen.randomInt(from, to);
			Vector<Integer> features = new Vector<Integer>(count);

			for(int i=0;i<count;i++){
			  final String label;
			  final String comment;
			  if (PARETO) {
			    label = getParetoSentence(1, 3, valueGen);
			    comment = getParetoSentence(1, 1000, valueGen);
			  } else {
			    int labelNrWords = valueGen.randomInt(1, 3);
			    label = dictionary1.getRandomSentence(labelNrWords);
          
          int commentNrWords = valueGen.randomInt(20, 50);
          comment = dictionary2.getRandomSentence(commentNrWords); 
        }
				ProductFeature pf = new ProductFeature(productFeatureNr,label,comment);
				
				if(!namedGraph) {
					pf.setPublisher(1);
					pf.setPublishDate(publishDateGen.randomDateInMillis());
				}
				
				features.add(productFeatureNr);
				
				bundle.add(pf);
				
				productFeatureNr++;
			}
			pt.setFeatures(features);
		}
		bundle.commitToSerializer();
		System.out.println((productFeatureNr-1) + " Product Features generated.\n");
		
		dictionary1.deactivateLogging();
		dictionary2.deactivateLogging();
	}

	/*
	 * Generate seeds for producer data
	 */
	public static Long[] generateSeedsProducer() {
		Long[] seeds = new Long[5];
		for(int i=0;i<5;i++)
			seeds[i] = seedGenerator.nextLong();
		
		return seeds;
	}
	
	/*
	 * Generates the distribution data for producers
	 */
	public static void generateProducerDistribution(Long[] seeds) {
		NormalDistGenerator productCountGen = new NormalDistGenerator(3,1,avgProductsPerProducer,seeds[3]);
		Integer productNr = 1;
		
		while(productNr<=productCount) {
			//Now generate Products for this Producer
			int hasNrProducts = productCountGen.getValue();
			if(productNr+hasNrProducts-1 > productCount)
				hasNrProducts = productCount - productNr + 1;
			productNr += hasNrProducts;
			producerOfProduct.add(productNr-1);
		}
	}
	
	/*
	 * Creates the Producers and their Products
	 */
	public static void createProducerData(Long[] seeds)
	{
		System.out.println("Generating Producers and Products...");
		DateGenerator publishDateGen = new DateGenerator(new GregorianCalendar(2000,07,20),new GregorianCalendar(2005,06,23),seeds[0]);
		ValueGenerator valueGen = new ValueGenerator(seeds[1]);
		RandomBucket countryGen = createCountryGenerator(seeds[2]);
		Random productSeedGen = new Random(seeds[4]);
		
		ObjectBundle bundle = new ObjectBundle(serializer);
		
		int productNr = 1;
		int producerNr = 1;
		
		// Record the term frequencies
		dictionary1.activateLogging(anyProperty1gramList);
		dictionary2.activateLogging(anyProperty2gramList);
		while(producerNr<producerOfProduct.size())
		{
			//Generate Producer data
		  final String comment;
		  final String label;
      if (PARETO) {
        label = getParetoSentence(1, 3, valueGen);
        comment = getParetoSentence(1, 1000, valueGen);
      } else {
        int labelNrWords = valueGen.randomInt(1, 3);
        label = dictionary1.getRandomSentence(labelNrWords);

        final int commentNrWords = valueGen.randomInt(20, 50);
        comment = dictionary2.getRandomSentence(commentNrWords);        
      }
			
			String homepage = TextGenerator.getProducerWebpage(producerNr);
			
			String country = (String)countryGen.getRandom();
			
			Producer p = new Producer(producerNr,label,comment,homepage,country);
			
			//Generate Publisher data
			if(!namedGraph) {
				p.setPublisher(producerNr);
				p.setPublishDate(publishDateGen.randomDateInMillis());
				bundle.setPublisher(p.toString());
				bundle.setPublisherNum(p.getNr());
			}
			else {
				bundle.setPublisher(p.toString());
				bundle.setPublishDate(publishDateGen.randomDateInMillis());
				bundle.setGraphName("<" + Producer.getProducerNS(p.getNr()) + "Graph-" + DateGenerator.formatDate(bundle.getPublishDate()) + ">");
				bundle.setPublisherNum(p.getNr());
			}
			
			bundle.add(p);
			
			int hasNrProducts = producerOfProduct.get(producerNr) - producerOfProduct.get(producerNr-1);
			createProductsOfProducer(bundle, producerNr, productNr, hasNrProducts, productSeedGen);
			
			//All data for current producer generated -> commit (Important for NG-Model).
			bundle.commitToSerializer();
			
			productNr += hasNrProducts;
			producerNr++;
		}
		System.out.println((producerNr - 1) + " Producers and " + (productNr - 1) + " Products have been generated.\n");
		// Deactivate logging
		dictionary1.deactivateLogging();
		dictionary2.deactivateLogging();
	}

	/*
	 * Creates the Products of the specified producer
	 */
	private static void createProductsOfProducer(ObjectBundle bundle, Integer producer, Integer productNr, Integer hasNrProducts, Random productSeedGen)
	{
		DateGenerator publishDateGen = new DateGenerator(new GregorianCalendar(2000,9,20),new GregorianCalendar(2007,0,23),productSeedGen.nextLong());
		
		dictionary2.activateLogging(anyProperty2gramList);
		
		ValueGenerator valueGen = new ValueGenerator(productSeedGen.nextLong());
		NormalDistRangeGenerator productTypeBroker = new NormalDistRangeGenerator(0,1,productTypeLeaves.size(),2, productSeedGen.nextLong());
		NormalDistRangeGenerator numPropertyGen = new NormalDistRangeGenerator(0,1,2000,2, productSeedGen.nextLong());
		
		//For assigning a type out of 3 possible types
		RandomBucket productPropertyTypeGen = new RandomBucket(3,productSeedGen.nextLong());
		productPropertyTypeGen.add(40, Integer.valueOf(1));
		productPropertyTypeGen.add(20, Integer.valueOf(2));
		productPropertyTypeGen.add(40, Integer.valueOf(3));
		
		//For choosing ProductFeatures and ProductProperties
		RandomBucket true25 = new RandomBucket(2,productSeedGen.nextLong());
		true25.add(75, Boolean.valueOf(false));
		true25.add(25, Boolean.valueOf(true));
		
		//For choosing ProductProperties
		RandomBucket true50 = new RandomBucket(2,productSeedGen.nextLong());
		true50.add(50, Boolean.valueOf(false));
		true50.add(50, Boolean.valueOf(true));

    //We want to record used words for product labels
		final HashMap<String, Integer> labelGram1 = new HashMap<String, Integer>();
		final HashMap<String, Integer> labelGram2 = new HashMap<String, Integer>();
		
		for(int nr = productNr; nr<productNr+hasNrProducts;nr++)
		{
			//Generate Product data
		  final String label;
      final String comment;
      if (PARETO) {
        dictionary1.activateLogging(labelGram1);
        dictionary2.activateLogging(labelGram2);
        label = getParetoSentence(1, 3, valueGen);
        
        dictionary1.activateLogging(anyProperty1gramList);
        dictionary2.activateLogging(anyProperty2gramList);
        comment = getParetoSentence(1, 1000, valueGen);
      } else {
        //We want to record used words for product labels
        dictionary1.activateLogging(labelGram1);
        int labelNrWords = valueGen.randomInt(1, 3);
        label = dictionary1.getRandomSentence(labelNrWords);
        
        int commentNrWords = valueGen.randomInt(50, 150);
        comment = dictionary2.getRandomSentence(commentNrWords);        
      }
			
			ProductType productType = productTypeLeaves.get(productTypeBroker.getValue()-1);
			
			int productPropertyType = (Integer)productPropertyTypeGen.getRandom();
	
			//Generating Product Properties
			Integer[] numProperties = new Integer[6];
			String[] textProperties = new String[6]; 
			for(int i=0; i<3; i++){
				numProperties[i] = numPropertyGen.getValue();
				if (PARETO) {
	        textProperties[i] = getParetoSentence(3, 15, valueGen);
				} else {
				  int nrWords = valueGen.randomInt(3, 15);
	        textProperties[i] = dictionary2.getRandomSentence(nrWords);
				}
			}
			
			//ProductProperty4
			numProperties[3] = null;
			textProperties[3] = null;
			boolean hasNum = false;
			boolean hasText = false;
			
			if(productPropertyType==1)
				hasNum = hasText = true;

			if(productPropertyType==2) {
				hasNum = (Boolean)true50.getRandom();
				hasText = (Boolean)true50.getRandom();
			}
			
			if(hasNum)
				numProperties[3] = numPropertyGen.getValue();
			
			if(hasText) {
        if (PARETO) {
          textProperties[3] = getParetoSentence(3, 15, valueGen);
        } else {
          int nrWords = valueGen.randomInt(3, 15);
          textProperties[3] = dictionary2.getRandomSentence(nrWords);
        }
			}
			
			//ProductProperty5
			numProperties[4] = null;
			textProperties[4] = null;
			hasNum = false;
			hasText = false;
			
			if(productPropertyType==1)
				hasNum = hasText = true;
			
			if(productPropertyType==2 || productPropertyType==3) {
				hasNum = (Boolean)true25.getRandom();
				hasText = (Boolean)true25.getRandom();
			}
			
			if(hasNum)
				numProperties[4] = numPropertyGen.getValue();
			
			if(hasText) {
        if (PARETO) {
          textProperties[4] = getParetoSentence(3, 15, valueGen);
        } else {
          int nrWords = valueGen.randomInt(3, 15);
          textProperties[4] = dictionary2.getRandomSentence(nrWords);
        }
			}
			
			//ProductProperty6
			numProperties[5] = null;
			textProperties[5] = null;
			if(productPropertyType==3) {
				if((Boolean)true50.getRandom())
					numProperties[5] = numPropertyGen.getValue();
				if((Boolean)true50.getRandom()) {
	        if (PARETO) {
	          textProperties[5] = getParetoSentence(3, 15, valueGen);
	        } else {
	          int nrWords = valueGen.randomInt(3, 15);
	          textProperties[5] = dictionary2.getRandomSentence(nrWords);
	        }
				}
			}
			
			//Assigning Product Features
			Vector<Integer> features = new Vector<Integer>();
			ProductType tempPT = productType;
			while(tempPT.getParent()!=null)
			{
				Iterator<Integer> it = tempPT.getFeatures().iterator();
				while(it.hasNext()) {
					Integer feature = it.next();
					if((Boolean)true25.getRandom())
						features.add(feature);
				}
				
				tempPT = tempPT.getParent();
			}
			
			Product p = new Product(nr,label,comment, productType, producer);
			
			p.setProductPropertyNumeric(numProperties);
			p.setProductPropertyTextual(textProperties);
			p.setFeatures(features);
				
			//Generate Publisher data
			if(!namedGraph) {
				p.setPublisher(producer);
				p.setPublishDate(publishDateGen.randomDateInMillis());
			}
			
			// Decide if the product goes to the update dataset
			if(generateUpdateDataset && nr>=nrOfMinProductNrForUpdate)
				updateResourceData.get(nr-nrOfMinProductNrForUpdate).add(p);
			else
				bundle.add(p);	
		}
		appendWordList(anyProperty1gramList, labelGram1);
		appendWordList(anyProperty2gramList, labelGram2);
		wordList.putAll(labelGram1);
		wordList.putAll(labelGram2);
		dictionary1.deactivateLogging();
		dictionary2.deactivateLogging();
	}
	
	private static void appendWordList(Map<String, Integer> sink, Map<String, Integer> src) {
    for (Entry<String, Integer> frq : src.entrySet()) {
      final String key = frq.getKey();
      final Integer value = frq.getValue();
      if (sink.containsKey(frq.getKey()))
        sink.put(key, sink.get(key) + value);
      else
        sink.put(key, value);
    }
  }

	/*
	 * Returns the ProducerNr of given ProductNr
	 */
	public static Integer getProducerOfProduct(Integer productNr) {
		Integer producerNr = Collections.binarySearch(producerOfProduct, productNr);
		if(producerNr<0)
			producerNr = - producerNr - 1;
		
		return producerNr;
	}
	
	/*
	 * Creates a country generator
	 */
	public static RandomBucket createCountryGenerator(Long seed)
	{
		RandomBucket countryGen = new RandomBucket(10, seed);
		
		countryGen.add(40, "US");
		countryGen.add(10, "GB");
		countryGen.add(10, "JP");
		countryGen.add(10, "CN");
		countryGen.add(5, "DE");
		countryGen.add(5, "FR");
		countryGen.add(5, "ES");
		countryGen.add(5, "RU");
		countryGen.add(5, "KR");
		countryGen.add(5, "AT");
		
		return countryGen;
	}
	
	/*
	 * Generate seeds for vendor data
	 */
	public static Long[] generateSeedsVendor() {
		Long[] seeds = new Long[5];
		for(int i=0;i<5;i++)
			seeds[i] = seedGenerator.nextLong();
		
		return seeds;
	}
	
	/*
	 * Generates the distribution data for producers
	 */
	public static void generateVendorDistribution(Long[] seeds) {
		NormalDistGenerator offerCountGenerator = new NormalDistGenerator(3,1,avgOffersPerVendor,seeds[3]);

		long offerNr = 1;
		
		while(offerNr<=offerCount) {
			long offerCountVendor = (long)offerCountGenerator.getValue();
			if(offerNr+offerCountVendor-1 > offerCount)
				offerCountVendor = offerCount - offerNr + 1;
			
			offerNr += offerCountVendor;
			vendorOfOffer.add(offerNr-1);
		}
	}
	
	/*
	 * Creates the Vendors
	 */
	public static void createVendorData(Long[] seeds)
	{
		System.out.println("Generating Vendors and their Offers...");
		DateGenerator publishDateGen = new DateGenerator(new GregorianCalendar(2000,9,20),new GregorianCalendar(2007,0,23),seeds[0]);
		ValueGenerator valueGen = new ValueGenerator(seeds[1]);
		RandomBucket countryGen = createCountryGenerator(seeds[2]);
		Random offerSeedGen = new Random(seeds[4]);
		
		ObjectBundle bundle = new ObjectBundle(serializer);
		
		Long offerNr = (long) 1;
		Integer vendorNr = 1;
		
		// Turn on logging
		dictionary1.activateLogging(anyProperty1gramList);
		dictionary2.activateLogging(anyProperty2gramList);
		
		while(offerNr<=offerCount)
		{
			//Generate Vendor data
			final String label;
			final String comment;
			if (PARETO) {
				label = getParetoSentence(1, 3, valueGen);
				comment = getParetoSentence(1, 1000, valueGen);
			} else {
				int labelNrWords = valueGen.randomInt(1, 3);
				label = dictionary1.getRandomSentence(labelNrWords);
        
				int commentNrWords = valueGen.randomInt(20, 50);
				comment = dictionary2.getRandomSentence(commentNrWords);        
			}
			
			String homepage = TextGenerator.getVendorWebpage(vendorNr);
			
			String country = (String)countryGen.getRandom();
			
			Vendor v = new Vendor(vendorNr,label,comment,homepage,country);
			
			//Generate Publisher data
			if(!namedGraph) {
				v.setPublisher(vendorNr);
				v.setPublishDate(publishDateGen.randomDateInMillis(today.getTimeInMillis()-(97*DateGenerator.oneDayInMillis), today.getTimeInMillis()));
				bundle.setPublisher(v.toString());
				bundle.setPublisherNum(v.getNr());
			}
			else {
				bundle.setPublisher(v.toString());
				bundle.setPublishDate(publishDateGen.randomDateInMillis());
				bundle.setGraphName("<" + Vendor.getVendorNS(v.getNr()) + "Graph-" + DateGenerator.formatDate(bundle.getPublishDate()) + ">");
				bundle.setPublisherNum(v.getNr());
			}
			
			bundle.add(v);
			
			//Get number of offers for this Vendor
			Long offerCountVendor = vendorOfOffer.get(vendorNr) - vendorOfOffer.get(vendorNr-1);;
	
			createOffersOfVendor(bundle, vendorNr, offerNr, offerCountVendor, valueGen, offerSeedGen);
			
			//All data for current producer generated -> commit (Important for NG-Model).
			bundle.commitToSerializer();
			
			offerNr += offerCountVendor;
			vendorNr++;
		}
		System.out.println((vendorNr-1) + " Vendors and " + (offerNr - 1) + " Offers have been generated.\n");
		// Turn Off logging
		dictionary1.deactivateLogging();
		dictionary2.deactivateLogging();
	}

	/*
	 * Creates the offers for a product
	 */
	public static void createOffersOfVendor(ObjectBundle bundle, Integer vendor, Long offerNr, Long hasNrOffers, ValueGenerator valueGen, Random offerSeedGen)
	{
		NormalDistRangeGenerator deliveryDaysGen = new NormalDistRangeGenerator(2,1,21,14.2,offerSeedGen.nextLong());
		NormalDistRangeGenerator productNrGen = new NormalDistRangeGenerator(2,1,productCount,4,offerSeedGen.nextLong());
		DateGenerator dateGen = new DateGenerator(offerSeedGen.nextLong());
		
		for(long nr=offerNr;nr<offerNr+hasNrOffers;nr++)
		{
			int product = productNrGen.getValue();
			double price = valueGen.randomDouble(5, 10000);
			Long publishDate;
			if(namedGraph)
				publishDate = bundle.getPublishDate();
			else
				publishDate = dateGen.randomDateInMillis(today.getTimeInMillis()-(97*DateGenerator.oneDayInMillis), today.getTimeInMillis());
			
			Long validFrom = publishDate - (valueGen.randomInt(0, 90)*DateGenerator.oneDayInMillis);
			Long validTo = publishDate + (valueGen.randomInt(7, 90)*DateGenerator.oneDayInMillis);
			int deliveryDays = deliveryDaysGen.getValue();
			String webpage = Vendor.getVendorNS(vendor) + "Offer" + nr + "/";
			
			Offer offer = new Offer(nr, product, vendor, price, validFrom, validTo, deliveryDays, webpage);
			
			if(!namedGraph) {
				offer.setPublishDate(publishDate);
				offer.setPublisher(vendor);
			}
			if(generateUpdateDataset && product>=nrOfMinProductNrForUpdate)
				updateResourceData.get(product-nrOfMinProductNrForUpdate).add(offer);
			else
				bundle.add(offer);
		}
	}

	/*
	 * Generate seeds for rating site data
	 */
	public static Long[] generateSeedsRatingSite() {
		Long[] seeds = new Long[8];
		for(int i=0;i<8;i++)
			seeds[i] = seedGenerator.nextLong();
		
		return seeds;
	}
	
	/*
	 * Generates the distribution data for producers
	 */
	public static void generateRatingSiteDistribution(Long[] seeds) {
		NormalDistGenerator reviewCountPRSGen = new NormalDistGenerator(3,1, avgReviewsPerRatingSite ,seeds[6]);
		NormalDistGenerator reviewCountPPGen = new NormalDistGenerator(3,1, avgReviewsPerPerson ,seeds[7]);
		
		Long reviewNr = (long) 1;
		Integer personNr = 1;
		Integer ratingSiteNr = 1;
		
		while(reviewNr<=reviewCount) {
			//Get number of reviews for this Rating Site
			Long reviewCountRatingSite = (long)reviewCountPRSGen.getValue();
			if(reviewNr+reviewCountRatingSite > reviewCount)
				reviewCountRatingSite = reviewCount - reviewNr + 1;
			
			Long maxReviewForRatingSite = reviewNr+reviewCountRatingSite;

			while(reviewNr < maxReviewForRatingSite)
			{
				Long reviewCountPerson = (long)reviewCountPPGen.getValue0();
				if(reviewNr+reviewCountPerson > maxReviewForRatingSite)
					reviewCountPerson = maxReviewForRatingSite - reviewNr;
				
				personNr++;
				reviewNr += reviewCountPerson;
			}
			
			ratingsiteOfReview.add(reviewNr-1);
			ratingSiteNr++;
		}
	}
	
	/*
	 * Creates the Reviewers
	 */
	public static void createRatingSiteData(Long[] seeds)
	{
		System.out.println("Generating RatingSite Data: Reviewers and Reviews... ");
		DateGenerator publishDateGen = new DateGenerator(new GregorianCalendar(2008,5,20),new GregorianCalendar(2008,8,23),seeds[0]);
		ValueGenerator valueGen = new ValueGenerator(seeds[1]);
		RandomBucket countryGen = createCountryGenerator(seeds[2]);
		
		//For Review Generation
		DateGenerator reviewDateGen = new DateGenerator(182,today,seeds[3]);
		RandomBucket true70 = new RandomBucket(2, seeds[4]);
		NormalDistRangeGenerator productNrGen = new NormalDistRangeGenerator(2,1,productCount,4,seeds[5]);
		true70.add(70, Boolean.valueOf(true));
		true70.add(30, Boolean.valueOf(false));
		
		NormalDistGenerator reviewCountPRSGen = new NormalDistGenerator(3,1, avgReviewsPerRatingSite ,seeds[6]);
		NormalDistGenerator reviewCountPPGen = new NormalDistGenerator(3,1, avgReviewsPerPerson ,seeds[7]);
		
		Long reviewNr = (long) 1;
		Integer personNr = 1;
		Integer ratingSiteNr = 1;
		
		ObjectBundle bundle = new ObjectBundle(serializer);
		
		while(reviewNr<=reviewCount)
		{
			//Get number of reviews for this Rating Site
			Long reviewCountRatingSite = (long)reviewCountPRSGen.getValue();
			if(reviewNr+reviewCountRatingSite > reviewCount)
				reviewCountRatingSite = reviewCount - reviewNr + 1;
			
			//Generate provenance data for this rating site
			if(namedGraph) {
				bundle.setPublisher(RatingSite.getURIref(ratingSiteNr));
				bundle.setPublishDate(publishDateGen.randomDateInMillis());
				bundle.setGraphName("<" + RatingSite.getRatingSiteNS(ratingSiteNr) + "Graph-" + DateGenerator.formatDate(bundle.getPublishDate()) + ">");
				bundle.setPublisherNum(ratingSiteNr);
			} 
			else {
				bundle.setPublisher(RatingSite.getURIref(ratingSiteNr));
				bundle.setPublisherNum(ratingSiteNr);
			}
			//Now generate persons and reviews
			Long maxReviewForRatingSite = reviewNr+reviewCountRatingSite;

			while(reviewNr < maxReviewForRatingSite)
			{		
				//Generate Person data
				String name = dictionary3.getRandomSentence(1);
				
				String country = (String)countryGen.getRandom();
				
				String mbox_sha1 = valueGen.randomSHA1();
				
				Person p = new Person(personNr,name,country,mbox_sha1);
				
				//Generate Publisher data
				if(!namedGraph) {
					p.setPublishDate(publishDateGen.randomDateInMillis());
				}
				//needed for qualified name
				p.setPublisher(ratingSiteNr);
			
				bundle.add(p);
			
				//Now generate Reviews for this Person
				Long reviewCountPerson = (long)reviewCountPPGen.getValue0();
				if(reviewNr+reviewCountPerson > maxReviewForRatingSite)
					reviewCountPerson = maxReviewForRatingSite - reviewNr;
				
				createReviewsOfPerson(bundle, p, reviewNr, reviewCountPerson, valueGen, reviewDateGen,
									  productNrGen, publishDateGen, true70);
				personNr++;
				reviewNr += reviewCountPerson;
			}
			
			//All data for current producer generated -> commit (Important for NG-Model).
			bundle.commitToSerializer();
			
			ratingSiteNr++;
		}
		System.out.println((ratingSiteNr - 1) + " Rating Sites with " + (personNr - 1) + " Persons and " + (reviewNr-1) + " Reviews have been generated.\n");
	}
	
	
	/*
	 * Creates the reviews for a person
	 */
	private static void createReviewsOfPerson(ObjectBundle bundle, Person person, Long reviewNr, Long count,
			ValueGenerator valueGen, DateGenerator dateGen, NormalDistRangeGenerator prodNrGen,
			DateGenerator publishDateGen, RandomBucket true70)
	{
    dictionary1.activateLogging(anyProperty1gramList);
    dictionary2.activateLogging(anyProperty2gramList);
    
		for(int i=0;i<count;i++)
		{
      Integer[] ratings = new Integer[4];
      
      for(int j=0;j<4;j++)
        if((Boolean)true70.getRandom())
          ratings[j] = valueGen.randomInt(1, 10);
        else
          ratings[j] = null;
      
			int product = prodNrGen.getValue();
			int producerOfProduct = getProducerOfProduct(product);
			int personNr = person.getNr();
			Long reviewDate = dateGen.randomDateInMillis(today.getTimeInMillis()-DateGenerator.oneDayInMillis*365,today.getTimeInMillis());
			final String title;
			final String text;
			if (PARETO) {
        title = getParetoSentence(1, 50, valueGen);
			  text = reviewDict.getReviewSentence(ratings, 1, 1000, valueGen, paretoLiteralLength);
			} else {
			  final int titleCount = valueGen.randomInt(4, 15);
	      title = dictionary2.getRandomSentence(titleCount);
	      
	      final int textCount = valueGen.randomInt(50, 200);
	      text = dictionary2.getRandomSentence(textCount);
			}
			int language = ISO3166.countryCodes.get(person.getCountryCode());
			
			Review review = new Review(reviewNr, product, personNr, reviewDate, title, text, ratings, language, producerOfProduct);
			
			if(!namedGraph) {
				review.setPublishDate(publishDateGen.randomDateInMillis(reviewDate, today.getTimeInMillis()));
			}
			//needed for qualified name
			review.setPublisher(person.getPublisher());
			
			if(generateUpdateDataset && product>=nrOfMinProductNrForUpdate)
				updateResourceData.get(product-nrOfMinProductNrForUpdate).add(review);
			else
				bundle.add(review);

			reviewNr++;
		}
		// Turn off terms frequency recording
		dictionary1.deactivateLogging();
		dictionary2.deactivateLogging();
	}
	
	protected static void createUpdateDataset() {
		int productsInTransaction = 0;
		ObjectBundle bundle = new ObjectBundle(updateDatasetSerializer);
		for(List<BSBMResource> productData: updateResourceData) {
			for(BSBMResource res: productData)
				bundle.add(res);
			bundle.commitToSerializer();
			productsInTransaction = (productsInTransaction + 1) % nrOfProductsPerTransaction; 
			if(productsInTransaction==0)
				bundle.writeStringToSerializer(updateDatasetTransactionSeparator);
		}
		updateDatasetSerializer.serialize();
	}
	
	/*
	 * Process the program parameters typed on the command line.
	 */
	private static void processProgramParameters(String[] args) {
		int i=0;
		while(i<args.length) {
			try {
				String arg = args[i];
                if(arg.equals("-s")) {
					serializerType = args[i++ + 1];
				}
				else if(arg.equals("-pc")) {
					productCount = Integer.parseInt(args[i++ + 1]);
				}
				else if(arg.equals("-fc")) {
					forwardChaining = true;
				}
				else if(arg.equals("-dir")) {
					outputDirectory = args[i++ + 1];
				}
				else if(arg.equals("-fn")) {
					outputFileName = args[i++ + 1];
				}
				else if(arg.equals("-ufn")) {
					updateDatasetFileName = args[i++ + 1];
				}
				else if(arg.equals("-nof")) {
					nrOfOutputFiles = Integer.parseInt(args[i++ + 1]);
				}
				else if(arg.equals("-ud")) {
					generateUpdateDataset = true;
				}
				else if(arg.equals("-tc")) {
					nrOfTransactionsInUpdateDataset = Integer.parseInt(args[i++ + 1]);
				}
				else if(arg.equals("-ppt")) {
					nrOfProductsPerTransaction = Integer.parseInt(args[i++ + 1]);
				}
				else if(arg.equals("-sep")) {
					updateDatasetTransactionSeparator = String.valueOf(args[i++ + 1]);
				}
				else if (arg.equals("-pareto")) {
				  PARETO = true;
				}
                // Hacked the data generator for only generating a range of data files 
				else if (arg.equals("-fromId")) {
					fromFileId = Integer.parseInt(args[i++ + 1]);
				}
				else if (arg.equals("-toId")) {
					toFileId = Integer.parseInt(args[i++ + 1]);
				}   
				else if (arg.equals("-nom")) {
					noOfMachines = Integer.parseInt(args[i++ + 1]);
				}
				else if (arg.equals("-mId")) {
					thisMachineId = Integer.parseInt(args[i++ + 1]);
				}                   
				else {
	                System.err.println("Invalid option:"+arg);
					printUsageInfos();
					System.exit(-1);
				}
				
				i++;
							
			} catch(Exception e) {
				System.err.println("Invalid arguments\n");
				printUsageInfos();
				System.exit(-1);
			}
		}
	}
	
	/*
	 * print command line options
	 */
	public static void printUsageInfos() {
		String output = "Usage:\n\n" +
						"Possible options are:\n" +
						"\t-s <output format>\n" +
						"\t\twhere <output format>: nt (N-Triples), ntgz (N-Tripes gzipped), trig (TriG), ttl (Turtle), ttlgz (Turtle gzipped), sql (MySQL dump),\n" +
						"\t\t\tvirt (Virtuoso SQL dump), monetdb (SQL), xml (XML dump)\n" +
						"\t\tdefault: nt\n" +
						"\t\tNote:\tBy chosing a named graph output format like TriG,\n\t\t\ta named graph model gets generated.\n" +
						"\t-pc <product count>\n" +
						"\t\tdefault: 100\n" +
						"\t-fc\tSwitch on forward chaining which is by default off\n" +
						"\t-dir <output directory>\n" +
						"\t\tThe output directory for the Test Driver data\n" +
						"\t\tdefault: td_data\n" +
						"\t-fn <dataset file name>\n" +
						"\t\tThe file name without the output format suffix\n" +
						"\t\tdefault: dataset\n" +
						"\t-ufn <update dataset file name>\n" +
						"\t\tThe file name without the output format suffix\n" +
						"\t\tdefault: dataset_update\n" +
						"\t-nof <number of output files>\n" +
						"\t\tThe number of output files. Only for -s nt or ttl\n" +
						"\t\tdefault: 1\n" +
						"\t-ud\tSwitch on generation of update dataset\n" +
						"\t-tc <number of update transactions>\n" +
						"\t\tShould be used in combination with -ud.\n" +
						"\t\tdefault: 1000\n" +
						"\t-ppt <number of products per update transactions>\n" +
						"\t\tShould be used in combination with -ud.\n" +
						"\t\tdefault: 1\n" +
						"\t-pareto\tUse the Pareto distribution for generating Product and Review data.\n";
		System.out.print(output);
	}
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		processProgramParameters(args);
		init();
		
		Long[] ptSeeds = generateSeedsProductType();
		Long[] pfSeeds = generateSeedsProductFeature();
		Long[] producerSeeds = generateSeedsProducer();
		Long[] vendorSeeds = generateSeedsVendor();
		Long[] rtSeeds = generateSeedsRatingSite();
		
		generateProducerDistribution(producerSeeds);
		generateVendorDistribution(vendorSeeds);
		generateRatingSiteDistribution(rtSeeds);
		
		createProductTypeHierarchy(ptSeeds);
		createProductFeatures(pfSeeds);
		createProducerData(producerSeeds);
		createVendorData(vendorSeeds);
		
		createRatingSiteData(rtSeeds);
		
		serializer.serialize();
		writeTestDriverData();
		
		if(generateUpdateDataset)
			createUpdateDataset();
		
		System.out.println(serializer.triplesGenerated() + " triples generated.");
		
		if(generateUpdateDataset)
			System.out.println(updateDatasetSerializer.triplesGenerated() + " triples generated for update dataset.");
	}
}