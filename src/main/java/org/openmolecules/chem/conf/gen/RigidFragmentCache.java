package org.openmolecules.chem.conf.gen;

import com.actelion.research.chem.Canonizer;
import com.actelion.research.chem.Coordinates;
import com.actelion.research.chem.IDCodeParserWithoutCoordinateInvention;
import com.actelion.research.chem.StereoMolecule;
import com.actelion.research.chem.io.CompoundFileParser;
import com.actelion.research.gui.FileHelper;
import com.actelion.research.util.DoubleFormat;

import java.io.*;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipInputStream;

/**
 * This class implements a thread-save, concurrent cache of rigid fragments' 3D-atom-coordinates.
 * It is accessed by the RigidFragmentProvider instances, which serve RigidFragments to
 * ConformerGenerator instances when constructing 3D-coordinates for molecules by assembling
 * them from 3-dimensional rigid fragments and torsion tables.
 * Typically, ConformerGenerators start with an empty cache that fills over time or with
 * a default cache, which is prefilled with many common fragments from organic and medicinal
 * chemistry as well as with common building block fragments.<br>
 * The default cache is balanced in memory footprint and number of fragments it contains.
 * For special purposes you may consider creating an own custom cache file using the createCacheFiles() method.
 **/
public class RigidFragmentCache extends ConcurrentHashMap<String, RigidFragmentCache.CacheEntry> implements Serializable {
	private static final String DEFAULT_CACHE_FILE = "/resources/defaultRigidFragments.zip";
	private static RigidFragmentCache sInstance;
	private int mHitCount,mGetCount, mNonCachableCount;
	private boolean mDefaultCacheLoaded;
	private TreeSet<String> mSetOfLoadedCacheFiles;

	public static RigidFragmentCache getDefaultInstance() {
		if (sInstance != null)
			return sInstance;

		synchronized (RigidFragmentCache.class) {
			if (sInstance == null)
				sInstance = new RigidFragmentCache();
			return sInstance;
		}
	}

	public static RigidFragmentCache createInstance(String cacheFileName) {
		RigidFragmentCache cache = new RigidFragmentCache();
		if (cacheFileName != null)
			cache.loadCache(cacheFileName);
		return cache;
	}

	private RigidFragmentCache() {}

	@Override
	public void clear() {
		super.clear();
		mDefaultCacheLoaded = false;
	}

	@Override
	public RigidFragmentCache.CacheEntry get(Object key) {
		RigidFragmentCache.CacheEntry entry = super.get(key);
		mGetCount++;
		if (entry != null) {
			entry.incrementHitCount();
			mHitCount++;
			}
		return entry;
		}

	public double getHitQuote() {
		return (double)mHitCount/(double)mGetCount;
		}

	public int getHitCount() {
		return mHitCount;
		}

	public int getRequestCount() {
		return mGetCount;
		}

	public int getNonCachableCount() {
		return mNonCachableCount;
		}

	public void increaseNonCachableCount() {
		mNonCachableCount++;
		}

	public void resetAllCounters() {
		mNonCachableCount = 0;
		mHitCount = 0;
		mGetCount = 0;
		}

	/**
	 * Writes for every distinct fragment: one idcode, multiple encoded coordinate sets, multiple conformer likelihoods
	 * @param cacheFileName
	 */
	public boolean serializeCache(String cacheFileName, int minHits) {
		try{
			BufferedWriter bw = new BufferedWriter(new FileWriter(cacheFileName));
			for (String key : keySet()) {
				RigidFragmentCache.CacheEntry cacheEntry = super.get(key);  // we need super to not increment hit counter

				if (cacheEntry.hitCount >= minHits) {
					bw.write(key);
					bw.newLine();

					bw.write(Integer.toString(cacheEntry.coordinates.length));
					bw.newLine();

					StereoMolecule mol = new IDCodeParserWithoutCoordinateInvention().getCompactMolecule(key);
					Canonizer canonizer = new Canonizer(mol, Canonizer.COORDS_ARE_3D);
					for (Coordinates[] coords:cacheEntry.coordinates) {
						bw.write(canonizer.getEncodedCoordinates(true, coords));
						bw.newLine();
						canonizer.invalidateCoordinates();
					}

					for (double likelihood:cacheEntry.likelihood) {
						bw.write(DoubleFormat.toString(likelihood));
						bw.newLine();
					}
				}
			}
			bw.close();
			return true;
		} catch (IOException ex) {
			ex.printStackTrace();
		}
		return false;
	}

	/**
	 * This loads the default cache file
	 */
	public synchronized void loadDefaultCache() {
		if (!mDefaultCacheLoaded) {
			try {
				InputStream is = RigidFragmentCache.class.getResourceAsStream(DEFAULT_CACHE_FILE);
				if (is != null) {
					ZipInputStream zipStream = new ZipInputStream(is);
					zipStream.getNextEntry();
					BufferedReader reader = new BufferedReader(new InputStreamReader(zipStream));
					loadCache(reader);
					reader.close();
					mDefaultCacheLoaded = true;
					}
				}
			catch (Exception e) {
				e.printStackTrace();
				}
			}
		}

	/**
	 * Loads pre-calculated rigid fragment coordinates from a cache file, which is either a text file
	 * created by the createCacheFiles() method, or a zip archive of the text file.
	 * This method can be called multiple times to add conformer data from multiple sources.
	 * If the method is called with a cacheFileNam, which was loaded before, then it is not loaded a second time.
	 * @param cacheFileName text file or zipped text file with extension .zip
	 */
	public void loadCache(String cacheFileName) {
		if (mSetOfLoadedCacheFiles == null)
			mSetOfLoadedCacheFiles = new TreeSet();

		if (!mSetOfLoadedCacheFiles.contains(cacheFileName)) {
			try {
				BufferedReader reader;
				if (cacheFileName.endsWith(".zip")) {
					ZipInputStream zipStream = new ZipInputStream(new FileInputStream(cacheFileName));
					zipStream.getNextEntry();
					reader = new BufferedReader(new InputStreamReader(zipStream));
				}
				else {
					reader = new BufferedReader(new FileReader(cacheFileName));
				}
				loadCache(reader);
				reader.close();
			}
			catch (Exception e) {
				e.printStackTrace();
			}
			mSetOfLoadedCacheFiles.add(cacheFileName);
		}
	}

	private void loadCache(BufferedReader br) throws Exception {
		String idcode;
		while ((idcode = br.readLine()) != null) {
			IDCodeParserWithoutCoordinateInvention parser = new IDCodeParserWithoutCoordinateInvention();
			StereoMolecule mol = parser.getCompactMolecule(idcode);

			int count = Integer.parseInt(br.readLine());

			Coordinates[][] coords = new Coordinates[count][mol.getAllAtoms()];
			for (int i=0; i<count; i++) {
				for (int j=0; j<coords[i].length; j++)
					coords[i][j] = new Coordinates();
				parser.parseCoordinates(br.readLine().getBytes(), 0, mol, coords[i]);
			}

			double[] likelihood = new double[count];
			for (int i=0; i<count; i++)
				likelihood[i] = Double.parseDouble(br.readLine());

			put(idcode, new CacheEntry(coords, likelihood));
		}
	}

	/**
	 * This is a helper method to generate a custom cache and optionally a set of cache files from one or
	 * more compound files. You may use this function to create a custom fragment cache if the default cache
	 * file used by the ConformerGenerator in not adequate for your purpose. The default file covers many
	 * common fragments in organic and medicinal chemistry and common building block fragments.
	 * However, it is limited in size. You may consider using a custom cache file in these cases:<br>
	 * - To achieve a maximum of speed on the expense of memory, e.g. for a cloud based service that
	 * generates conformers on request.<br>
	 * - If you process molecules with limited diversity, e.g. combinatorial libraries as the Enamine REAL space.
	 * Then you may use a complete cache covering every existing fragment for maximum speed.<br>
	 * - If you store conformer sets as fragment references and torsion tables. Then your fragment cache
	 * needs a complete cache covering every existing fragment.<br>
	 * This method processes all input files, locates and all rigid fragments, produces one or more
	 * distinct conformers from the fragments and creates a new cache from them. Optionally, the
	 * fragment conformers can be energy minimized using the MMFF94s+ forcefield. Then multiple cache
	 * cache export files are written: with all cache entries, with entries used at least 2,3,5, and 10 times.
	 * The numbers 1,2,3,5,10 and .txt extention will be appended to the given cache file name.
	 * @param inputFileNames array of one or more input file paths (may be mixture of sdf and dwar)
	 * @param cacheFileName path and file name without any extention ('_n_.txt' will be added)
	 * @param optimizeFragments whether to energy minimize fragments using MMFF94s+
	 * @param maxCompoundsPerFile if an input file contains more compounds than this, then the rest are skipped
	 * @return created cache or null, if an input file could not be found
	 */
	public static RigidFragmentCache createCache(String[] inputFileNames, String cacheFileName,
                                boolean optimizeFragments, boolean verbose, int maxCompoundsPerFile) {
		boolean notFound = false;
		for (String ifn:inputFileNames)
			if (!FileHelper.fileExists(new File(ifn), 1000)) {
				System.out.println("File not found: '"+ifn+"'");
				notFound = true;
			}
		if (notFound)
			return null;

		RigidFragmentCache cache = createInstance(null);

		for (String ifn:inputFileNames) {
			long millis = addFragmentsToCache(cache, optimizeFragments, ifn, verbose, maxCompoundsPerFile);
			System.out.println("File '"+ifn+"' processed in "+millis+" milliseconds.");
		}

		if (inputFileNames != null) {
			System.out.print("Writing cache files... ");
			boolean success = cache.serializeCache(cacheFileName + "_1_.txt", 0)   // we have one hit less than usages
					&& cache.serializeCache(cacheFileName + "_2_.txt", 1)
					&& cache.serializeCache(cacheFileName + "_3_.txt", 2)
					&& cache.serializeCache(cacheFileName + "_5_.txt", 4)
					&& cache.serializeCache(cacheFileName + "_10_.txt", 9);
			System.out.println(success ? "done" : "failure !!!");
			}

		return cache;
	}

	private static long addFragmentsToCache(RigidFragmentCache cache, boolean optimizeFragments, String inputFile, boolean verbose, int maxCompounds) {
		long start_millis = System.currentTimeMillis();
		int compoundNo = 0;

		System.out.println("Processing '"+inputFile+"'... ('.' = 100 molecules)");

		CompoundFileParser parser = CompoundFileParser.createParser(inputFile);
		ConformerGenerator cg = new ConformerGenerator(123L, cache, optimizeFragments);

		while (parser.next() && compoundNo < maxCompounds) {
			if (verbose)
				System.out.println("\nFile:"+inputFile+" Compound:"+(1+compoundNo)+" idcode:"+parser.getIDCode());
			else {
				if (compoundNo % 50 == 49)
					System.out.print(".");
				if (compoundNo % 5000 == 4999) {
					System.out.println(" hit-rate:" + DoubleFormat.toString(cache.getHitQuote(), 5, false)
							+ " millis:" + (System.currentTimeMillis() - start_millis)
							+ " cacheSize:" + cache.size());
					cache.resetAllCounters();
				}
			}

			new ConformerGenerator(123L, cache, optimizeFragments).initialize(parser.getMolecule(), false);

			compoundNo++;
		}
		System.out.println();

		return System.currentTimeMillis() - start_millis;
	}

	public static class CacheEntry implements Comparable<CacheEntry> {
		Coordinates[][] coordinates;
		double[] likelihood;
		int hitCount;

		public CacheEntry(Coordinates[][] coordinates, double[] likelihoods) {
			this.coordinates = coordinates;
			this.likelihood = likelihoods;
		}

		public void incrementHitCount() {
			hitCount++;
		}

		@Override
		public int compareTo(CacheEntry o) {
			if (hitCount != o.hitCount)
				return hitCount < o.hitCount ? -1 : 1;
			return 0;
		}
	}
}
