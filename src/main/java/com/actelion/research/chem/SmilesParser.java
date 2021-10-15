/*
* Copyright (c) 1997 - 2016
* Actelion Pharmaceuticals Ltd.
* Gewerbestrasse 16
* CH-4123 Allschwil, Switzerland
*
* All rights reserved.
*
* Redistribution and use in source and binary forms, with or without
* modification, are permitted provided that the following conditions are met:
*
* 1. Redistributions of source code must retain the above copyright notice, this
*    list of conditions and the following disclaimer.
* 2. Redistributions in binary form must reproduce the above copyright notice,
*    this list of conditions and the following disclaimer in the documentation
*    and/or other materials provided with the distribution.
* 3. Neither the name of the the copyright holder nor the
*    names of its contributors may be used to endorse or promote products
*    derived from this software without specific prior written permission.
*
* THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
* ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
* WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
* DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
* ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
* (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
* LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
* ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
* (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
* SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*
*/

package com.actelion.research.chem;

import com.actelion.research.chem.coords.CoordinateInventor;
import com.actelion.research.chem.reaction.Reaction;
import com.actelion.research.util.ArrayUtils;
import com.actelion.research.util.SortedList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.TreeMap;


public class SmilesParser {
	private static final int SMARTS_MODE_MASK = 3;
	public static final int SMARTS_MODE_IS_SMILES = 0;
	public static final int SMARTS_MODE_GUESS = 1;
	public static final int SMARTS_MODE_IS_SMARTS = 2;

	public static final int MODE_SKIP_COORDINATE_TEMPLATES = 4;
	public static final int MODE_MAKE_HYDROGEN_EXPLICIT = 8;

	private static final int INITIAL_CONNECTIONS = 16;
	private static final int MAX_CONNECTIONS = 100; // largest allowed one in SMILES is 99
	private static final int MAX_BRACKET_LEVELS = 64;
	private static final int MAX_AROMATIC_RING_SIZE = 15;

	private static final int HYDROGEN_ANY = -1;

	// Unspecified hydrogen count within brackets means :=0 for SMILES and no-H-restriction for SMARTS.
	// Therefore, we have to distinguish from explicit H0, which defined query feature for SMARTS.
	private static final int HYDROGEN_IMPLICIT_ZERO = 9;

	private StereoMolecule mMol;
	private boolean[] mIsAromaticBond;
	private int mAromaticAtoms,mAromaticBonds,mSmartsMode,mCoordinateMode;
	private boolean mCreateSmartsWarnings, mMakeHydrogenExplicit;
	private StringBuilder mSmartsWarningBuffer;

	/**
	 * Creates a new SmilesParser that doesn't allow SMARTS features to be present in
	 * parsed strings. SMARTS features cause an exception. The fragment flag of created
	 * molecules is never set.
	 */
	public SmilesParser() {
		this(SMARTS_MODE_IS_SMILES, false);
		}

	/**
	 * Creates a new SmilesParser that may or may not allow SMARTS features to be present in
	 * parsed strings. If smartsMode is SMARTS_MODE_IS_SMILES, then any SMARTS features cause
	 * an exception. If smartsMode is SMARTS_MODE_IS_SMARTS, then the input string is considered
	 * a SMARTS, e.g. 'CC' is taken as fragment of two non-aromatic carbon atoms connected by a
	 * single bond and without any implicit hydrogen atoms. If smartsMode is SMARTS_MODE_IS_GUESS,
	 * then
	 * molecules is never set.
	 * @param mode one of SMARTS_MODE... and optionally other mode flags
	 * @param createSmartsWarnings if true, then getSmartsWarning() may be used after parsing a SMILES or SMARTS
	 */
	public SmilesParser(int mode, boolean createSmartsWarnings) {
		mSmartsMode = mode & SMARTS_MODE_MASK;
		mCreateSmartsWarnings = createSmartsWarnings;
		mMakeHydrogenExplicit = ((mode & MODE_MAKE_HYDROGEN_EXPLICIT) != 0);
		mCoordinateMode = CoordinateInventor.MODE_DEFAULT;
		if ((mode & MODE_SKIP_COORDINATE_TEMPLATES) != 0)
			mCoordinateMode |= CoordinateInventor.MODE_SKIP_DEFAULT_TEMPLATES;
		if (mMakeHydrogenExplicit)
			mCoordinateMode &= ~CoordinateInventor.MODE_REMOVE_HYDROGEN;
		}

	public StereoMolecule parseMolecule(String smiles) {
		return smiles == null ? null : parseMolecule(smiles.getBytes());
		}

	/**
	 * Convenience method to quickly obtain a StereoMolecule from a SMILES string.
	 * If you process many SMILES, then the parse() methods are preferred, because
	 * they avoid the steady instantiation new StereoMolecules.
	 * @param smiles
	 * @return
	 */
	public StereoMolecule parseMolecule(byte[] smiles) {
		StereoMolecule mol = new StereoMolecule();
		try {
			parse(mol, smiles);
			}
		catch (Exception e) {
			return null;
			}
		return mol;
		}

	public Reaction parseReaction(String smiles) throws Exception {
		return smiles == null ? null : parseReaction(smiles.getBytes());
	}

	public Reaction parseReaction(byte[] smiles) throws Exception {
		int index1 = ArrayUtils.indexOf(smiles, (byte)'>');
		int index2 = (index1 == -1) ? -1 : ArrayUtils.indexOf(smiles, (byte)'>', index1+1);
		if (index2 == -1)
			throw new Exception("Missing one or both separators ('>').");
		if (ArrayUtils.indexOf(smiles, (byte)'>', index2+1) != -1)
			throw new Exception("Found more than 2 separators ('>').");

		Reaction rxn = new Reaction();

		int start = 0;
		while (start < index1) {
			int index = ArrayUtils.indexOf(smiles, (byte)'.', start+1);
			if (index != -1 && index+2 < index1 && smiles[index+1] == '.') {
				StereoMolecule reactant = new StereoMolecule();
				parse(reactant, smiles, start, index);
				rxn.addReactant(reactant);
				start = index + 2;
				continue;
				}

			StereoMolecule reactants = new StereoMolecule();
			parse(reactants, smiles, start, index1);
			rxn.addReactant(reactants);
			break;
			}

		if (index2 - index1 > 1) {
			start = index1+1;
			while (start < index2) {
				int index = ArrayUtils.indexOf(smiles, (byte)'.', start+1);
				if (index != -1 && index+2 < index2 && smiles[index+1] == '.') {
					StereoMolecule catalyst = new StereoMolecule();
					parse(catalyst, smiles, start, index);
					rxn.addCatalyst(catalyst);
					start = index + 2;
					continue;
					}

				StereoMolecule catalysts = new StereoMolecule();
				parse(catalysts, smiles, start, index2);
				rxn.addCatalyst(catalysts);
				break;
				}
			}

		start = index2+1;
		while (start < smiles.length) {
			int index = ArrayUtils.indexOf(smiles, (byte)'.', start+1);
			if (index != -1 && index+2 < smiles.length && smiles[index+1] == '.') {
				StereoMolecule product = new StereoMolecule();
				parse(product, smiles, start, index);
				rxn.addProduct(product);
				start = index + 2;
				continue;
				}

			StereoMolecule products = new StereoMolecule();
			parse(products, smiles, start, smiles.length);
			rxn.addProduct(products);
			break;
			}

		return rxn;
		}

	/**
	 * If createSmartsWarning in the constructor was passed as true, then this method
	 * returns a list of all SMARTS features, which could not be interpreted in the most recently
	 * parsed SMILES/SMARTS pattern.
	 * @return
	 */
	public String getSmartsWarning() {
		return mSmartsWarningBuffer == null ? "" : "Unresolved SMARTS features:"+mSmartsWarningBuffer;
		}

	/**
	 * Parses the given smiles into the molecule, creates proper atom coordinates
	 * to reflect correct double bond geometries and translates tetrahedral and allene
	 * parities into up/down-bonds. SMARTS features are neglected unless
	 * setAllowSmartsFeatures(true) was called before parsing.
	 * @param mol
	 * @param smiles
	 * @throws Exception
	 */
	public void parse(StereoMolecule mol, String smiles) throws Exception {
		parse(mol, smiles.getBytes(), true, true);
		}

	public void parse(StereoMolecule mol, byte[] smiles) throws Exception {
		parse(mol, smiles, true, true);
		}

	public void parse(StereoMolecule mol, byte[] smiles, int position, int endIndex) throws Exception {
		parse(mol, smiles, position, endIndex, true, true);
		}

	public void parse(StereoMolecule mol, byte[] smiles, boolean createCoordinates, boolean readStereoFeatures) throws Exception {
		parse(mol, smiles, 0, smiles.length, createCoordinates, readStereoFeatures);
		}

	public void parse(StereoMolecule mol, byte[] smiles, int position, int endIndex, boolean createCoordinates, boolean readStereoFeatures) throws Exception {
		mMol = mol;
		mMol.clear();

		if (mSmartsWarningBuffer != null)
			mSmartsWarningBuffer.setLength(0);

		mAromaticAtoms = 0;
		boolean allowSmarts = (mSmartsMode != SMARTS_MODE_IS_SMILES);

		TreeMap<Integer,THParity> parityMap = null;

		int[] baseAtom = new int[MAX_BRACKET_LEVELS];
		baseAtom[0] = -1;

		int[] ringClosureAtom = new int[INITIAL_CONNECTIONS];
		int[] ringClosurePosition = new int[INITIAL_CONNECTIONS];
		int[] ringClosureBondType = new int[INITIAL_CONNECTIONS];
		int[] ringClosureBondQueryFeatures = new int[INITIAL_CONNECTIONS];
		for (int i = 0; i<INITIAL_CONNECTIONS; i++)
			ringClosureAtom[i] = -1;

		int atomMass = 0;
		int fromAtom = -1;
		boolean squareBracketOpen = false;
		boolean isDoubleDigit = false;
		boolean smartsFeatureFound = false;
		int bracketLevel = 0;
		int bondType = Molecule.cBondTypeSingle;
		int bondQueryFeatures = 0;
		SortedList<Integer> atomList = new SortedList<>();

		while (smiles[position] <= 32)
			position++;

		while (position < endIndex) {
			char theChar = (char)smiles[position++];

			if (Character.isLetter(theChar)
			 || theChar == '*'
			 || theChar == '?'
			 || (theChar == '!' && allowSmarts && squareBracketOpen)
			 || (theChar == '#' && allowSmarts && squareBracketOpen)) { // TODO not-lists
				int atomicNo = -1;
				int charge = 0;
				int mapNo = 0;
				int abnormalValence = -1;
				int explicitHydrogens = HYDROGEN_ANY;
				boolean parityFound = false;
				boolean isClockwise = false;
				int atomQueryFeatures = 0;      // translated from obvious SMARTS features
				if (squareBracketOpen) {
					if (theChar == 'R' && Character.isDigit(smiles[position])) {
						int noOfDigits = Character.isDigit(smiles[position+1]) ? 2 : 1;
						atomicNo = Molecule.getAtomicNoFromLabel(new String(smiles, position-1, 1+noOfDigits));
						position += noOfDigits;
						}
					else if (theChar == '*') {
						atomicNo = 6;
						atomQueryFeatures |= Molecule.cAtomQFAny;
						}
					else if (theChar == '?') {
						atomicNo = 0;
						}
					else if (theChar == '#') {
						int number = 0;
						while (position < endIndex
						 && Character.isDigit(smiles[position])) {
							number = 10 * number + smiles[position] - '0';
							position++;
							}
						if (number < 1 || number >= Molecule.cAtomLabel.length)
							throw new Exception("SmilesParser: Atomic number out of range.");
						atomicNo = number;
						}
					else {
						boolean isNot = (theChar == '!');
						if (isNot) {
							smartsFeatureFound = true;
							atomQueryFeatures |= Molecule.cAtomQFAny;
							position++;
							}

						int labelLength = Character.isLowerCase(smiles[position]) ? 2 : 1;
						atomicNo = Molecule.getAtomicNoFromLabel(new String(smiles, position-1, labelLength));
						position += labelLength-1;
						explicitHydrogens = HYDROGEN_IMPLICIT_ZERO;

						// If we have a comma after the first atom label, then we need to parse a list.
						// In this case we also have to set aromaticity query features from upper and lower case symbols.
						if (allowSmarts && (smiles[position] == ',' || isNot)) {
							atomList.removeAll();
							boolean upperCaseFound = false;
							boolean lowerCaseFound = false;
							int start = position - labelLength;
							for (int p=start; p<smiles.length; p++) {
								if (!Character.isLetter(smiles[p])) {
									int no = Molecule.getAtomicNoFromLabel(new String(smiles, start, p - start));
									if (no != 0) {
										atomList.add(no);
										if (Character.isUpperCase(smiles[start]))
											upperCaseFound = true;
										else
											lowerCaseFound = true;
										}
									start = p+1;
									if (smiles[p] != ',')
										break;
									if (smiles[p+1] == '!') {
										if (!isNot)
											throw new Exception("SmilesParser: inconsistent '!' in atom list.");
										p++;
										start++;
										}
									}
								}
							if (atomList.size() > 1) {
								if (!upperCaseFound)
									atomQueryFeatures |= Molecule.cAtomQFAromatic;
								else if (!lowerCaseFound)
									atomQueryFeatures |= Molecule.cAtomQFNotAromatic;
								}

							position = start-1;
							}
						}

					while (squareBracketOpen) {
						if (smiles[position] == '@') {
							position++;
							if (smiles[position] == '@') {
								isClockwise = true;
								position++;
								}
							parityFound = true;
							continue;
							}

						if (smiles[position] == ':') {
							position++;
							while (Character.isDigit(smiles[position])) {
								mapNo = 10 * mapNo + smiles[position] - '0';
								position++;
								}
							continue;
							}

						if (smiles[position] == '[')
							throw new Exception("SmilesParser: nested square brackets found");

						if (smiles[position] == ']') {
							position++;
							squareBracketOpen = false;
							continue;
							}

						if (smiles[position] == '+') {
							charge = 1;
							position++;
							while (smiles[position] == '+') {
								charge++;
								position++;
								}
							if (charge == 1 && Character.isDigit(smiles[position])) {
								charge = smiles[position] - '0';
								position++;
								}
							// explicit charge=0 is usually meant as query feature
							if (charge == 0)
								atomQueryFeatures |= Molecule.cAtomQFNotChargeNeg | Molecule.cAtomQFNotChargePos;
							continue;
							}

						if (smiles[position] == '-') {
							charge = -1;
							position++;
							while (smiles[position] == '-') {
								charge--;
								position++;
								}
							if (charge == -1 && Character.isDigit(smiles[position])) {
								charge = '0' - smiles[position];
								position++;
								}
							// explicit charge=0 is usually meant as query feature
							if (charge == 0)
								atomQueryFeatures |= Molecule.cAtomQFNotChargeNeg | Molecule.cAtomQFNotChargePos;
							continue;
							}

						boolean isNot = (smiles[position] == '!');
						if (isNot)
							position++;

						if (smiles[position] == 'H') {
							position++;
							explicitHydrogens = 1;
							if (Character.isDigit(smiles[position])) {
								explicitHydrogens = smiles[position] - '0';
								position++;
								}
							if (isNot) {
								if (explicitHydrogens == 0)
									atomQueryFeatures |= Molecule.cAtomQFNot0Hydrogen;
								else if (explicitHydrogens == 1)
									atomQueryFeatures |= Molecule.cAtomQFNot1Hydrogen;
								else if (explicitHydrogens == 2)
									atomQueryFeatures |= Molecule.cAtomQFNot2Hydrogen;
								else if (explicitHydrogens == 3)
									atomQueryFeatures |= Molecule.cAtomQFNot3Hydrogen;
								explicitHydrogens = HYDROGEN_ANY;
								}
							continue;
							}

						if (smiles[position] == 'D') {   // non-H-neighbours
							position++;
							int neighbours = 1;
							if (Character.isDigit(smiles[position])) {
								neighbours = smiles[position] - '0';
								position++;
								}
							int qf = (neighbours == 0) ? Molecule.cAtomQFNot0Neighbours
								   : (neighbours == 1) ? Molecule.cAtomQFNot1Neighbour
								   : (neighbours == 2) ? Molecule.cAtomQFNot2Neighbours
								   : (neighbours == 3) ? Molecule.cAtomQFNot3Neighbours
								   : (neighbours == 4) ? Molecule.cAtomQFNot4Neighbours : 0;
							if (qf != 0) {
								if (!isNot)
									qf = qf ^ Molecule.cAtomQFNeighbours;
								atomQueryFeatures |= qf;
								}
							continue;
							}

						if (smiles[position] == 'A' || smiles[position] == 'a') {
							position++;
							atomQueryFeatures |= (isNot ^ smiles[position] == 'A') ? Molecule.cAtomQFNotAromatic : Molecule.cAtomQFAromatic;
							continue;
							}

						if (smiles[position] == 'R') {
							position++;
							if (!Character.isDigit(smiles[position])) {
								if (isNot)
									atomQueryFeatures |= Molecule.cBondQFRingState & ~Molecule.cAtomQFNotChain;
								else
									atomQueryFeatures |= Molecule.cAtomQFNotChain;
								continue;
								}
							int ringCount = smiles[position] - '0';
							position++;
							if (isNot) {
								if (ringCount == 0)
									atomQueryFeatures |= Molecule.cAtomQFNotChain;
								else if (ringCount == 1)
									atomQueryFeatures |= Molecule.cAtomQFNot2RingBonds;
								else if (ringCount == 2)
									atomQueryFeatures |= Molecule.cAtomQFNot3RingBonds;
								else if (ringCount == 3)
									atomQueryFeatures |= Molecule.cAtomQFNot4RingBonds;
								else
									smartsWarning("!R"+ringCount);
								}
							else {
								if (ringCount >= 3)
									ringCount = 3;
								if (ringCount == 0)
									atomQueryFeatures |= Molecule.cAtomQFRingState & ~Molecule.cAtomQFNotChain;
								else if (ringCount == 1)
									atomQueryFeatures |= Molecule.cAtomQFRingState & ~Molecule.cAtomQFNot2RingBonds;
								else if (ringCount == 2)
									atomQueryFeatures |= Molecule.cAtomQFRingState & ~Molecule.cAtomQFNot3RingBonds;
								else if (ringCount == 3)
									atomQueryFeatures |= Molecule.cAtomQFRingState & ~Molecule.cAtomQFNot4RingBonds;
								else
									smartsWarning("R"+ringCount);
								}
							continue;
							}

						if (smiles[position] == 'r') {
							position++;
							if (!Character.isDigit(smiles[position])) {
								if (isNot)
									atomQueryFeatures |= Molecule.cBondQFRingState & ~Molecule.cAtomQFNotChain;
								else
									atomQueryFeatures |= Molecule.cAtomQFNotChain;
								continue;
								}
							int ringSize = smiles[position] - '0';
							position++;
							if (!isNot && ringSize >= 3 && ringSize <= 7)
								atomQueryFeatures |= (ringSize << Molecule.cAtomQFRingSizeShift);
							else
								smartsWarning((isNot ? "!r" : "r") + ringSize);
							continue;
							}

						if (smiles[position] == 'v') {
							position++;
							int valence = smiles[position] - '0';
							position++;
							if (Character.isDigit(smiles[position])) {
								valence = 10 * valence + smiles[position] - '0';
								position++;
								}
							if (!isNot && valence <= 14)
								abnormalValence = valence;
							else
								smartsWarning((isNot ? "!v" : "v") + valence);
							continue;
							}

						if (allowSmarts && (smiles[position] == ';' || smiles[position] == '&')) { // we interpret high and low precendence AND the same way
							smartsFeatureFound = true;
							position++;
							continue;
							}

						throw new Exception("SmilesParser: unexpected character inside brackets: '"+theChar+"'");
						}
					}
				else if (theChar == '*') {
					atomicNo = 6;
					atomQueryFeatures |= Molecule.cAtomQFAny;
					}
				else if (theChar == '?') {
					atomicNo = 0;
					}
				else {
					switch (Character.toUpperCase(theChar)) {
					case 'A':
						atomicNo = 6;
						atomQueryFeatures |= Molecule.cAtomQFAny;
						atomQueryFeatures |= theChar == 'A' ? Molecule.cAtomQFNotAromatic : Molecule.cAtomQFAromatic;
						break;
					case 'B':
						if (position < endIndex && smiles[position] == 'r') {
							atomicNo = 35;
							position++;
							}
						else
							atomicNo = 5;
						break;
					case 'C':
						if (position < endIndex && smiles[position] == 'l') {
							atomicNo = 17;
							position++;
							}
						else
							atomicNo = 6;
						break;
					case 'F':
						atomicNo = 9;
						break;
					case 'I':
						atomicNo = 53;
						break;
					case 'N':
						atomicNo = 7;
						break;
					case 'O':
						atomicNo = 8;
						break;
					case 'P':
						atomicNo = 15;
						break;
					case 'S':
						atomicNo = 16;
						break;
						}
					}

				///////////////////////////////////////////////////////////////////////////////
				// At this position the atom is determined and the square bracket is closed! //
				///////////////////////////////////////////////////////////////////////////////

				if (atomicNo == -1 && theChar != '?')
					throw new Exception("SmilesParser: unknown element label found");

				int atom = mMol.addAtom(atomicNo);	// this may be a hydrogen, if defined as [H]
				mMol.setAtomCharge(atom, charge);
				mMol.setAtomMapNo(atom, mapNo, false);
				mMol.setAtomAbnormalValence(atom, abnormalValence);
				if (atomQueryFeatures != 0) {
					smartsFeatureFound = true;
					mMol.setAtomQueryFeature(atom, atomQueryFeatures, true);
					}
				if (atomList.size() != 0) {
					smartsFeatureFound = true;
					int[] list = new int[atomList.size()];
					for (int i=0; i<atomList.size(); i++)
						list[i] = atomList.get(i);
					mMol.setAtomList(atom, list);
					}

				// mark aromatic atoms
				if (Character.isLowerCase(theChar)) {
					if (atomicNo != 5 && atomicNo != 6 && atomicNo != 7 && atomicNo != 8 && atomicNo != 15 &&atomicNo != 16 && atomicNo != 33  && atomicNo != 34)
						throw new Exception("SmilesParser: atomicNo "+atomicNo+" must not be aromatic");

					mMol.setAtomMarker(atom, true);
					mAromaticAtoms++;
					}
				else {
					mMol.setAtomMarker(atom, false);
					}

				// put explicitHydrogen into atomCustomLabel to keep atom-relation when hydrogens move to end of atom list in handleHydrogen()
				if (explicitHydrogens != HYDROGEN_ANY && atomicNo != 1) {	// no custom labels for hydrogen to get useful results in getHandleHydrogenMap()
					byte[] bytes = new byte[1];
					bytes[0] = (byte)(explicitHydrogens == HYDROGEN_IMPLICIT_ZERO ? 0 : explicitHydrogens);
					mMol.setAtomCustomLabel(atom, bytes);
					}

				fromAtom = baseAtom[bracketLevel];
				if (baseAtom[bracketLevel] != -1 && bondType != Molecule.cBondTypeDeleted) {
					int bond = mMol.addBond(baseAtom[bracketLevel], atom, bondType);
					if (bondQueryFeatures != 0) {
						smartsFeatureFound = true;
						mMol.setBondQueryFeature(bond, bondQueryFeatures, true);
						}
					}

				// Reset bond type and query features to default.
				bondType = Molecule.cBondTypeSingle;
				bondQueryFeatures = 0;

				baseAtom[bracketLevel] = atom;
				if (atomMass != 0) {
					mMol.setAtomMass(atom, atomMass);
					atomMass = 0;
					}

				if (readStereoFeatures) {
					THParity parity = (parityMap == null) ? null : parityMap.get(fromAtom);
					if (parity != null)	// if previous atom is a stereo center
						parity.addNeighbor(atom, position, atomicNo==1 && atomMass==0);

					if (parityFound) {	// if this atom is a stereo center
						if (parityMap == null)
							parityMap = new TreeMap<Integer,THParity>();
	
						// using position as hydrogenPosition is close enough
						int hydrogenCount = (explicitHydrogens == HYDROGEN_IMPLICIT_ZERO) ? 0 : explicitHydrogens;
						parityMap.put(atom, new THParity(atom, fromAtom, hydrogenCount, position-1, isClockwise));
						}
					}

				continue;
				}

			if (theChar == '.') {
				baseAtom[bracketLevel] = -1;
				bondType = Molecule.cBondTypeDeleted;
				continue;
				}

			if (isBondSymbol(theChar)) {
				int excludedBonds = 0;
				while (isBondSymbol(theChar)) {
					if (theChar == '!') {
						theChar = (char)smiles[position++];
						if (theChar == '@')
							bondQueryFeatures |= Molecule.cBondQFNotRing;
						if ((theChar == '-' && smiles[position] == '>')
						 || (theChar == '<' && smiles[position] == '-')) {
							excludedBonds |= Molecule.cBondTypeMetalLigand;
							position++;
							}
						else if (theChar == '-')
							excludedBonds |= Molecule.cBondQFSingle;
						else if (theChar == '=')
							excludedBonds |= Molecule.cBondQFDouble;
						else if (theChar == '#')
							excludedBonds |= Molecule.cBondQFTriple;
						else if (theChar == ':')
							excludedBonds |= Molecule.cBondQFDelocalized;
						}
					else {
						if (theChar == '@')
							bondQueryFeatures |= Molecule.cBondQFRing;
						else if (theChar == '=')
							bondType = Molecule.cBondTypeDouble;
						else if (theChar == '#')
							bondType = Molecule.cBondTypeTriple;
						else if (theChar == ':')
							bondType = Molecule.cBondTypeDelocalized;
						else if (theChar == '/') {
							if (readStereoFeatures)
								bondType = Molecule.cBondTypeUp;    // encode slash temporarily in bondType
							}
						else if (theChar == '\\') {
							if (readStereoFeatures)
								bondType = Molecule.cBondTypeDown;  // encode slash temporarily in bondType
							}

						// Smiles extention 'dative bond'
						else if ((theChar == '-' && smiles[position] == '>')
						 || (theChar == '<' && smiles[position] == '-')) {
								bondType = Molecule.cBondTypeMetalLigand;
								position++;
							}

						if (smiles[position] == ',') {
							bondQueryFeatures |= bondSymbolToQueryFeature(bondType == Molecule.cBondTypeMetalLigand ? '>' : theChar);
							while (smiles[position] == ',') {
								if ((smiles[position+1] == '<' && smiles[position+2] == '-')
								 || (smiles[position+1] == '-' && smiles[position+2] == '>')) {
									bondQueryFeatures |= bondSymbolToQueryFeature('>');
									position += 3;
									}
								else {
									bondQueryFeatures |= bondSymbolToQueryFeature((char)smiles[position+1]);
									position += 2;
									}
								}
							}
						}

					if (smiles[position] == ';') {
						position++;
						theChar = (char)smiles[position++];
						continue;
						}

					if (excludedBonds != 0)
						bondQueryFeatures |= Molecule.cBondQFBondTypes & ~excludedBonds;

					break;
					}

				continue;
				}

			if (theChar <= ' ') {	// we stop reading at whitespace
				position = endIndex;
				continue;
				}

			if (Character.isDigit(theChar)) {
				int number = theChar - '0';
				if (squareBracketOpen) {
					while (position < endIndex
					 && Character.isDigit(smiles[position])) {
						number = 10 * number + smiles[position] - '0';
						position++;
						}
					atomMass = number;
					}
				else {
					boolean hasBondType = (smiles[position-2] == '-'
										|| smiles[position-2] == '/'
										|| smiles[position-2] == '\\'
										|| smiles[position-2] == '='
										|| smiles[position-2] == '#'
										|| smiles[position-2] == ':'
										|| smiles[position-2] == '>');
					if (isDoubleDigit
					 && position < endIndex
					 && Character.isDigit(smiles[position])) {
						number = 10 * number + smiles[position] - '0';
						isDoubleDigit = false;
						position++;
						}
					if (number >= ringClosureAtom.length) {
						if (number >=MAX_CONNECTIONS)
							throw new Exception("SmilesParser: ringClosureAtom number out of range");

						int oldSize = ringClosureAtom.length;
						int newSize = ringClosureAtom.length;
						while (newSize <= number)
							newSize = Math.min(MAX_CONNECTIONS, newSize + INITIAL_CONNECTIONS);

						ringClosureAtom = Arrays.copyOf(ringClosureAtom, newSize);
						ringClosurePosition = Arrays.copyOf(ringClosurePosition, newSize);
						ringClosureBondType = Arrays.copyOf(ringClosureBondType, newSize);
						ringClosureBondQueryFeatures = Arrays.copyOf(ringClosureBondQueryFeatures, newSize);
						for (int i=oldSize; i<newSize; i++)
							ringClosureAtom[i] = -1;
						}
					if (ringClosureAtom[number] == -1) {
						ringClosureAtom[number] = baseAtom[bracketLevel];
						ringClosurePosition[number] = position-1;
						ringClosureBondType[number] = hasBondType ? bondType : -1;
						ringClosureBondQueryFeatures[number] = hasBondType ? bondQueryFeatures : 0;
						}
					else {
						if (ringClosureAtom[number] == baseAtom[bracketLevel])
							throw new Exception("SmilesParser: ring closure to same atom");

						if (readStereoFeatures && parityMap != null) {
							THParity parity = parityMap.get(ringClosureAtom[number]);
							if (parity != null)
								parity.addNeighbor(baseAtom[bracketLevel], ringClosurePosition[number], false);
							parity = parityMap.get(baseAtom[bracketLevel]);
							if (parity != null)
								parity.addNeighbor(ringClosureAtom[number], position-1, false);
							}

						if (ringClosureBondType[number] != -1)
							bondType = ringClosureBondType[number];
						else if (bondType == Molecule.cBondTypeUp)	// interpretation inverts, if we have the slash bond at the second closure digit rather than at the first
							bondType = Molecule.cBondTypeDown;
						else if (bondType == Molecule.cBondTypeDown)
							bondType = Molecule.cBondTypeUp;
						// ringClosureAtom is the parent atom, i.e. the baseAtom of the first occurrence of the closure digit
						int bond = mMol.addBond(ringClosureAtom[number], baseAtom[bracketLevel], bondType);
						if (ringClosureBondQueryFeatures[number] != 0)
							bondQueryFeatures = ringClosureBondQueryFeatures[number];
						if (bondQueryFeatures != 0) {
							smartsFeatureFound = true;
							mMol.setBondQueryFeature(bond, ringClosureBondQueryFeatures[number], true);
							}
						ringClosureAtom[number] = -1;	// for number re-usage
						}
					bondType = Molecule.cBondTypeSingle;
					bondQueryFeatures = 0;
					}
				continue;
				}

			if (theChar == '+') {
				throw new Exception("SmilesParser: '+' found outside brackets");
				}

			if (theChar == '(') {
				if (baseAtom[bracketLevel] == -1)
					throw new Exception("Smiles with leading parenthesis are not supported");
				baseAtom[bracketLevel+1] = baseAtom[bracketLevel];
				bracketLevel++;
				continue;
				}

			if (theChar == ')') {
				bracketLevel--;
				continue;
				}

			if (theChar == '[') {
				squareBracketOpen = true;
				continue;
				}

			if (theChar == ']') {
				throw new Exception("SmilesParser: closing bracket at unexpected position");
				}

			if (theChar == '%') {
				isDoubleDigit = true;
				continue;
				}

/*			if (theChar == '.') {
				if (bracketLevel != 0)
					throw new Exception("SmilesParser: '.' found within brackets");
				baseAtom[0] = -1;
//				for (int i=0; i<ringClosureAtom.length; i++)	we allow ringClosures between fragments separated by '.'
//					ringClosureAtom[i] = -1;
				continue;
				}*/

			throw new Exception("SmilesParser: unexpected character outside brackets: '"+theChar+"'");
			}

		// Check for unsatisfied open bonds
		if (bondType != Molecule.cBondTypeSingle)
			throw new Exception("SmilesParser: dangling open bond");
		for (int rca:ringClosureAtom)
			if (rca != -1)
				throw new Exception("SmilesParser: dangling ring closure");

		int[] handleHydrogenAtomMap = mMol.getHandleHydrogenMap();

		// If the number of explicitly defined hydrogens conflicts with the occupied and default valence,
		// then try to change radical state to compensate. If that is impossible, then set an abnormal valence.
		mMol.setHydrogenProtection(true);	// We may have a fragment. Therefore, prevent conversion of explicit H into a query feature.
		mMol.ensureHelperArrays(Molecule.cHelperNeighbours);
		for (int atom=0; atom<mMol.getAllAtoms(); atom++) {
			if (mMol.getAtomCustomLabel(atom) != null) {	// if we have the exact number of hydrogens
				int explicitHydrogen = mMol.getAtomCustomLabelBytes(atom)[0];

				if (mMakeHydrogenExplicit) {
					for (int i=0; i<explicitHydrogen; i++)
						mMol.addBond(atom, mMol.addAtom(1), 1);
					}
				else if (smartsFeatureFound || mSmartsMode == SMARTS_MODE_IS_SMARTS) {
					if (explicitHydrogen == 0)
						mMol.setAtomQueryFeature(atom, Molecule.cAtomQFHydrogen & ~Molecule.cAtomQFNot0Hydrogen, true);
					if (explicitHydrogen == 1)
						mMol.setAtomQueryFeature(atom, Molecule.cAtomQFHydrogen & ~Molecule.cAtomQFNot1Hydrogen, true);
					if (explicitHydrogen == 2)
						mMol.setAtomQueryFeature(atom, Molecule.cAtomQFHydrogen & ~Molecule.cAtomQFNot2Hydrogen, true);
					if (explicitHydrogen == 3)
						mMol.setAtomQueryFeature(atom, Molecule.cAtomQFHydrogen & ~Molecule.cAtomQFNot3Hydrogen, true);
					}
				else {
					if (!mMol.isMarkedAtom(atom)) {
						// We don't correct aromatic atoms, because for aromatic atoms the number of
						// explicit hydrogens encodes whether a pi-bond needs to be placed at the atom
						// when resolving aromaticity.
						byte[] valences = Molecule.getAllowedValences(mMol.getAtomicNo(atom));
						boolean compatibleValenceFound = false;
						int usedValence = mMol.getOccupiedValence(atom);
						usedValence -= mMol.getElectronValenceCorrection(atom, usedValence);
						usedValence += explicitHydrogen;
						for (byte valence:valences) {
							if (usedValence <= valence) {
								compatibleValenceFound = true;
								if (valence == usedValence + 2)
									mMol.setAtomRadical(atom, Molecule.cAtomRadicalStateT);
								else if (valence == usedValence + 1)
									mMol.setAtomRadical(atom, Molecule.cAtomRadicalStateD);
								else if (valence != usedValence || valence != valences[0])
									mMol.setAtomAbnormalValence(atom, usedValence);
								break;
								}
							}
						if (!compatibleValenceFound)
							mMol.setAtomAbnormalValence(atom, usedValence);
						}

					if (!mMol.supportsImplicitHydrogen(atom)) {
						// If implicit hydrogens are not supported, then add explicit ones.
						for (int i=0; i<explicitHydrogen; i++)
							mMol.addBond(atom, mMol.addAtom(1), 1);
						}
					}
				}
			else if (!mMakeHydrogenExplicit && (smartsFeatureFound || mSmartsMode == SMARTS_MODE_IS_SMARTS)) {
				// if we don't have a hydrogen count on the atom, but we have explicit hydrogen atoms
				// and if we decode a SMARTS, then we convert explicit hydrogens into an 'at least n hydrogen'
				int explicitHydrogen = mMol.getExplicitHydrogens(atom);
				if (explicitHydrogen >= 1)
					mMol.setAtomQueryFeature(atom, Molecule.cAtomQFNot0Hydrogen, true);
				if (explicitHydrogen >= 2)
					mMol.setAtomQueryFeature(atom, Molecule.cAtomQFNot1Hydrogen, true);
				if (explicitHydrogen >= 3)
					mMol.setAtomQueryFeature(atom, Molecule.cAtomQFNot2Hydrogen, true);
				if (explicitHydrogen >= 4)
					mMol.setAtomQueryFeature(atom, Molecule.cAtomQFNot3Hydrogen, true);
				}
			}

		if (!mMakeHydrogenExplicit && (smartsFeatureFound || mSmartsMode == SMARTS_MODE_IS_SMARTS))
			mMol.removeExplicitHydrogens();

		mMol.ensureHelperArrays(Molecule.cHelperNeighbours);

		correctValenceExceededNitrogen();	// convert pyridine oxides and nitro into polar structures with valid nitrogen valences

		locateAromaticDoubleBonds(allowSmarts);

		mMol.removeAtomCustomLabels();
		mMol.setHydrogenProtection(false);

		if (readStereoFeatures) {
			assignKnownEZBondParities();

			if (parityMap != null) {
				for (THParity parity:parityMap.values())
					mMol.setAtomParity(parity.mCentralAtom, parity.calculateParity(handleHydrogenAtomMap), false);

				mMol.setParitiesValid(0);
				}
			}

		// defines unknown EZ parities as such, i.e. prevent coordinate generation to create implicit EZ-parities
		mMol.setParitiesValid(0);

		if (createCoordinates) {
			new CoordinateInventor(mCoordinateMode).invent(mMol);

			if (readStereoFeatures)
				mMol.setUnknownParitiesToExplicitlyUnknown();
			}

		if (smartsFeatureFound || mSmartsMode == SMARTS_MODE_IS_SMARTS)
			mMol.setFragment(true);
		}


	private int parseAtomList(byte[] smiles, int start, SortedList<Integer> atomList) {
		atomList.removeAll();
		for (int p=start; p<smiles.length; p++) {
			if (!Character.isLetter(smiles[p])) {
				int atomicNo = Molecule.getAtomicNoFromLabel(new String(smiles, start, p - start));
				if (atomicNo != 0)
					atomList.add(atomicNo);
				start = p+1;
				if (smiles[p] != ',')
					break;
				}
			}

		return start-1;
		}

	private boolean isBondSymbol(char theChar) {
		return theChar == '-'
			|| theChar == '='
			|| theChar == '#'
			|| theChar == ':'
			|| theChar == '/'
			|| theChar == '\\'
			|| theChar == '<'
			|| theChar == '!'
			|| theChar == '@';
		}

	private int bondSymbolToQueryFeature(char symbol) {
		return symbol == '=' ? Molecule.cBondQFDouble
			 : symbol == '#' ? Molecule.cBondQFTriple
			 : symbol == ':' ? Molecule.cBondQFDelocalized
			 : symbol == '>' ? Molecule.cBondQFMetalLigand : Molecule.cBondQFSingle;
		}

	private void smartsWarning(String feature) {
		if (mCreateSmartsWarnings) {
			if (mSmartsWarningBuffer == null)
				mSmartsWarningBuffer = new StringBuilder();

			mSmartsWarningBuffer.append(" ");
			mSmartsWarningBuffer.append(feature);
			}
		}

	private void locateAromaticDoubleBonds(boolean allowSmartsFeatures) throws Exception {
		mMol.ensureHelperArrays(Molecule.cHelperNeighbours);
		mIsAromaticBond = new boolean[mMol.getBonds()];
		mAromaticBonds = 0;

		// all explicitly defined aromatic bonds are taken
		for (int bond=0; bond<mMol.getBonds(); bond++) {
			if (mMol.getBondType(bond) == Molecule.cBondTypeDelocalized) {
				mMol.setBondType(bond, Molecule.cBondTypeSingle);
				mIsAromaticBond[bond] = true;
				mAromaticBonds++;
				}
			}

		boolean[] isAromaticRingAtom = new boolean[mMol.getAtoms()];

		// assume all bonds of small rings to be aromatic if the ring consists of aromatic atoms only
		RingCollection ringSet = new RingCollection(mMol, RingCollection.MODE_SMALL_AND_LARGE_RINGS);
		boolean[] isAromaticRing = new boolean[ringSet.getSize()];
		for (int ring=0; ring<ringSet.getSize(); ring++) {
			int[] ringAtom = ringSet.getRingAtoms(ring);
			isAromaticRing[ring] = true;
			for (int i=0; i<ringAtom.length; i++) {
				if (!mMol.isMarkedAtom(ringAtom[i])) {
					isAromaticRing[ring] = false;
					break;
					}
				}
			if (isAromaticRing[ring]) {
				for (int i=0; i<ringAtom.length; i++)
					isAromaticRingAtom[ringAtom[i]] = true;

				int[] ringBond = ringSet.getRingBonds(ring);
				for (int i=0; i<ringBond.length; i++) {
					if (!mIsAromaticBond[ringBond[i]]) {
						mIsAromaticBond[ringBond[i]] = true;
						mAromaticBonds++;
						}
					}
				}
			}

		// if ring bonds with two aromaticity markers are left, check whether
		// these are a member of a large ring that has all atoms marked as aromatic.
		// If yes then assume all of its bonds aromatic.
		for (int bond=0; bond<mMol.getBonds(); bond++) {
			if (!mIsAromaticBond[bond]
			 && ringSet.getBondRingSize(bond) != 0
			 && mMol.isMarkedAtom(mMol.getBondAtom(0, bond))
			 && mMol.isMarkedAtom(mMol.getBondAtom(1, bond))) {
				addLargeAromaticRing(bond);
				}
			}

		// If both atoms of a bond are marked as aromatic and
		// if none of the two atoms is a member of a fully aromatic ring,
		// then assume the bond to be an aromatic one.
		for (int bond=0; bond<mMol.getBonds(); bond++) {
			if (!mIsAromaticBond[bond]) {
				int atom1 = mMol.getBondAtom(0, bond);
				int atom2 = mMol.getBondAtom(1, bond);
				if (!isAromaticRingAtom[atom1]
				 && !isAromaticRingAtom[atom2]
				 && mMol.isMarkedAtom(atom1)
				 && mMol.isMarkedAtom(atom2)) {
					mIsAromaticBond[bond] = true;
					mAromaticBonds++;
					}
				}
			}

		mMol.ensureHelperArrays(Molecule.cHelperRings);	// to accomodate for the structure changes

		// Since Smiles don't have aromaticity information about bonds, we assume that all
		// bonds of a ring are aromatic if all of its atoms are aromatic. This is not always true
		// (e.g. in fbc@@@LdbbbbbRJvcEBMIpTqrAD@@@@@@@@), which may lead to wrong resolution of
		// conjugated double bonds leaving unpaired single aromatic atoms.
		// We cache the (untrustworthy) isAromaticBond array to later find paths between single
		// aromatic atoms.
		boolean[] isAromaticBond = new boolean[mMol.getBonds()];
		for (int i=0; i<mMol.getBonds(); i++)
			isAromaticBond[i] = mIsAromaticBond[i];

			// Some Smiles contain 'aromatic' rings with atoms not being compatible
			// with a PI-bond. These include: tertiary non-charged nitrogen, [nH],
			// sulfur, non-charged oxygen, charged carbon, etc...
			// All these atoms and attached bonds are marked as handled to avoid
			// attached bonds to be promoted (changed to double bond) later.
		for (int ring=0; ring<ringSet.getSize(); ring++) {
			if (isAromaticRing[ring]) {
				int[] ringAtom = ringSet.getRingAtoms(ring);
				for (int i=0; i<ringAtom.length; i++) {
					if (!qualifiesForPi(ringAtom[i])) {
						if (mMol.isMarkedAtom(ringAtom[i])) {
							mMol.setAtomMarker(ringAtom[i], false);// mark: atom aromaticity handled
							mAromaticAtoms--;
							}
						for (int j=0; j<mMol.getConnAtoms(ringAtom[i]); j++) {
							int connBond = mMol.getConnBond(ringAtom[i], j);
							if (mIsAromaticBond[connBond]) {
								mIsAromaticBond[connBond] = false;
								mAromaticBonds--;
								}
							}
						}
					}
				}
			}

		promoteObviousBonds();

		// promote fully delocalized 6-membered rings
		for (int ring=0; ring<ringSet.getSize(); ring++) {
			if (isAromaticRing[ring] && ringSet.getRingSize(ring) == 6) {
				int[] ringBond = ringSet.getRingBonds(ring);
				boolean isFullyDelocalized = true;
				for (int bond:ringBond) {
					if (!mIsAromaticBond[bond]) {
						isFullyDelocalized = false;
						break;
						}
					}
				if (isFullyDelocalized) {
					promoteBond(ringBond[0]);
					promoteBond(ringBond[2]);
					promoteBond(ringBond[4]);
					promoteObviousBonds();
					}
				}
			}

			// handle remaining annelated rings (naphtalines, azulenes, etc.) starting from bridge heads (qualifyingNo=5)
			// and then handle and simple rings (qualifyingNo=4)
		boolean qualifyingBondFound;
		for (int qualifyingNo=5; qualifyingNo>=4; qualifyingNo--) {
			do {
				qualifyingBondFound = false;
				for (int bond=0; bond<mMol.getBonds(); bond++) {
					if (mIsAromaticBond[bond]) {
						int aromaticConnBonds = 0;
						for (int i=0; i<2; i++) {
							int bondAtom = mMol.getBondAtom(i, bond);
							for (int j=0; j<mMol.getConnAtoms(bondAtom); j++)
								if (mIsAromaticBond[mMol.getConnBond(bondAtom, j)])
									aromaticConnBonds++;
							}

						if (aromaticConnBonds == qualifyingNo) {
							promoteBond(bond);
							promoteObviousBonds();
							qualifyingBondFound = true;
							break;
							}
						}
					}
				} while (qualifyingBondFound);
			}

		while (mAromaticAtoms >= 2)
			if (!connectConjugatedRadicalPairs(isAromaticBond))
				break;

		if (allowSmartsFeatures) {
			if (mAromaticAtoms != 0) {
				for (int atom=0; atom<mMol.getAtoms(); atom++) {
					if (mMol.isMarkedAtom(atom)) {
						mMol.setAtomMarker(atom, false);
						mMol.setAtomQueryFeature(atom, Molecule.cAtomQFAromatic, true);
						mAromaticAtoms--;
						}
					}
				}
			if (mAromaticBonds != 0) {
				for (int bond=0; bond<mMol.getBonds(); bond++) {
					if (mIsAromaticBond[bond]) {
						mIsAromaticBond[bond] = false;
						mMol.setBondType(bond, Molecule.cBondTypeDelocalized);
						mAromaticBonds--;
						}
					}
				}
			}
		else {
			for (int atom=0; atom<mMol.getAtoms(); atom++) {
				if (mMol.isMarkedAtom(atom) && mMol.getImplicitHydrogens(atom) != 0) {
					mMol.setAtomMarker(atom, false);
					mMol.setAtomRadical(atom, Molecule.cAtomRadicalStateD);
					mAromaticAtoms--;
					}
				}
			}

		if (mAromaticAtoms != 0)
			throw new Exception("Assignment of aromatic double bonds failed");
		if (mAromaticBonds != 0)
			throw new Exception("Assignment of aromatic double bonds failed");
		}


	private boolean connectConjugatedRadicalPairs(boolean[] isAromaticBond) {
		for (int atom=0; atom<mMol.getAtoms(); atom++) {
			if (mMol.isMarkedAtom(atom)) {
				int[] graphLevel = new int[mMol.getAtoms()];
				int graphAtom[] = new int[mMol.getAtoms()];
				int graphParent[] = new int[mMol.getAtoms()];

				graphAtom[0] = atom;
				graphLevel[atom] = 1;
				graphParent[atom] = -1;
				int current = 0;
				int highest = 0;
				while (current <= highest) {
					int bondOrder = ((graphLevel[graphAtom[current]] & 1) == 1) ? 1 : 2;
					for (int i=0; i<mMol.getConnAtoms(graphAtom[current]); i++) {
						int bond = mMol.getConnBond(graphAtom[current], i);
						if (mMol.getBondOrder(bond) == bondOrder && isAromaticBond[bond]) {
							int candidate = mMol.getConnAtom(graphAtom[current], i);
							if (graphLevel[candidate] == 0) {
								if (bondOrder == 1 && mMol.isMarkedAtom(candidate)) {
									int parent = graphAtom[current];
									while (parent != -1) {
										mMol.setBondType(mMol.getBond(candidate,  parent), bondOrder == 1 ?
												Molecule.cBondTypeDouble : Molecule.cBondTypeSingle);
										bondOrder = 3 - bondOrder;
										candidate = parent;
										parent = graphParent[parent];
										}

									mMol.setAtomMarker(atom, false);
									mMol.setAtomMarker(candidate, false);
									mAromaticAtoms -= 2;
									return true;
									}

								graphAtom[++highest] = candidate;
								graphParent[candidate] = graphAtom[current];
								graphLevel[candidate] = graphLevel[graphAtom[current]]+1;
								}
							}
						}
					current++;
					}
				}
			}
		return false;
		}

	private void addLargeAromaticRing(int bond) {
		int[] graphLevel = new int[mMol.getAtoms()];
		int graphAtom[] = new int[mMol.getAtoms()];
		int graphBond[] = new int[mMol.getAtoms()];
		int graphParent[] = new int[mMol.getAtoms()];

		int atom1 = mMol.getBondAtom(0, bond);
		int atom2 = mMol.getBondAtom(1, bond);
		graphAtom[0] = atom1;
		graphAtom[1] = atom2;
		graphBond[0] = -1;
		graphBond[1] = bond;
		graphLevel[atom1] = 1;
		graphLevel[atom2] = 2;
		graphParent[atom1] = -1;
		graphParent[atom2] = atom1;

		int current = 1;
		int highest = 1;
		while (current <= highest && graphLevel[graphAtom[current]] < MAX_AROMATIC_RING_SIZE) {
			int parent = graphAtom[current];
			for (int i=0; i<mMol.getConnAtoms(parent); i++) {
				int candidate = mMol.getConnAtom(parent, i);
				if (candidate != graphParent[parent]) {
					int candidateBond = mMol.getConnBond(parent, i);
					if (candidate == atom1) {	// ring closure
						graphBond[0] = candidateBond;
						for (int j=0; j<=highest; j++) {
							if (!mIsAromaticBond[graphBond[i]]) {
								mIsAromaticBond[graphBond[i]] = true;
								mAromaticBonds++;
								}
							}
						return;
						}
	
					if (mMol.isMarkedAtom(candidate)
					 && graphLevel[candidate] == 0) {
						highest++;
						graphAtom[highest] = candidate;
						graphBond[highest] = candidateBond;
						graphLevel[candidate] = graphLevel[parent]+1;
						graphParent[candidate] = parent;
						}
					}
				}
			current++;
			}
		return;
		}


	private boolean qualifiesForPi(int atom) {
		if (!RingCollection.qualifiesAsAromatic(mMol.getAtomicNo(atom)))
			return false;

		if ((mMol.getAtomicNo(atom) == 6 && mMol.getAtomCharge(atom) != 0)
		 || !mMol.isMarkedAtom(atom))	// already marked as hetero-atom of another ring
			return false;

		int explicitHydrogens = (mMol.getAtomCustomLabel(atom) == null) ?
								0 : mMol.getAtomCustomLabelBytes(atom)[0];
		int freeValence = mMol.getFreeValence(atom) - explicitHydrogens;
		if (freeValence < 1)
			return false;

		if (mMol.getAtomicNo(atom) == 16
		 || mMol.getAtomicNo(atom) == 34
		 || mMol.getAtomicNo(atom) == 52) {
			if (mMol.getConnAtoms(atom) == 2 && mMol.getAtomCharge(atom) <= 0)
				return false;
			if (freeValence == 2)
				return false;	// e.g. -S(=O)- correction to account for tetravalent S,Se
			}

		return true;
		}


	private void promoteBond(int bond) {
		if (mMol.getBondType(bond) == Molecule.cBondTypeSingle)
			mMol.setBondType(bond, Molecule.cBondTypeDouble);

		for (int i=0; i<2; i++) {
			int bondAtom = mMol.getBondAtom(i, bond);
			if (mMol.isMarkedAtom(bondAtom)) {
				mMol.setAtomMarker(bondAtom, false);
				mAromaticAtoms--;
				}
			for (int j=0; j<mMol.getConnAtoms(bondAtom); j++) {
				int connBond = mMol.getConnBond(bondAtom, j);
				if (mIsAromaticBond[connBond]) {
					mIsAromaticBond[connBond] = false;
					mAromaticBonds--;
					}
				}
			}
		}


	private void promoteObviousBonds() {
			// handle bond orders of aromatic bonds along the chains attached to 5- or 7-membered ring
		boolean terminalAromaticBondFound;
		do {
			terminalAromaticBondFound = false;
			for (int bond=0; bond<mMol.getBonds(); bond++) {
				if (mIsAromaticBond[bond]) {
					boolean isTerminalAromaticBond = false;
					for (int i=0; i<2; i++) {
						boolean aromaticNeighbourFound = false;
						int bondAtom = mMol.getBondAtom(i, bond);
						for (int j=0; j<mMol.getConnAtoms(bondAtom); j++) {
							if (bond != mMol.getConnBond(bondAtom, j)
							 && mIsAromaticBond[mMol.getConnBond(bondAtom, j)]) {
								aromaticNeighbourFound = true;
								break;
								}
							}
						if (!aromaticNeighbourFound) {
							isTerminalAromaticBond = true;
							break;
							}
						}

					if (isTerminalAromaticBond) {
						terminalAromaticBondFound = true;
						promoteBond(bond);
						}
					}
				}
			} while (terminalAromaticBondFound);
		}

	/**
	 * This corrects N=O double bonds where the nitrogen has an exceeded valence
	 * by converting to a single bond and introducing separated charges.
	 * (e.g. pyridinoxides and nitro groups)
	 */
	private void correctValenceExceededNitrogen() {
		for (int atom=0; atom<mMol.getAtoms(); atom++) {
			if (mMol.getAtomicNo(atom) == 7
			 && mMol.getAtomCharge(atom) == 0
			 && mMol.getOccupiedValence(atom) > 3
			 && mMol.getAtomPi(atom) > 0) {
				for (int i=0; i<mMol.getConnAtoms(atom); i++) {
					int connAtom = mMol.getConnAtom(atom, i);
					int connBond = mMol.getConnBond(atom, i);
					if ((mMol.getBondOrder(connBond) > 1)
					 && mMol.isElectronegative(connAtom)) {
						if (mMol.getBondType(connBond) == Molecule.cBondTypeTriple)
							mMol.setBondType(connBond, Molecule.cBondTypeDouble);
						else
							mMol.setBondType(connBond, Molecule.cBondTypeSingle);
	
						mMol.setAtomCharge(atom, mMol.getAtomCharge(atom) + 1);
						mMol.setAtomCharge(connAtom, mMol.getAtomCharge(connAtom) - 1);
						mMol.setAtomAbnormalValence(atom, -1);
						break;
						}
					}
				}
			}
		}

	private boolean assignKnownEZBondParities() {
		mMol.ensureHelperArrays(Molecule.cHelperRings);

		boolean paritiesFound = false;
		int[] refAtom = new int[2];
		int[] refBond = new int[2];
		int[] otherAtom = new int[2];
		for (int bond=0; bond<mMol.getBonds(); bond++) {
			if (!mMol.isSmallRingBond(bond)
			 && mMol.getBondType(bond) == Molecule.cBondTypeDouble) {
				for (int i=0; i<2; i++) {
					refAtom[i] = -1;
					otherAtom[i] = -1;
					int atom = mMol.getBondAtom(i, bond);
					for (int j=0; j<mMol.getConnAtoms(atom); j++) {
						int connBond = mMol.getConnBond(atom, j);
						if (connBond != bond) {
							if (refAtom[i] == -1
							 && (mMol.getBondType(connBond) == Molecule.cBondTypeUp
							  || mMol.getBondType(connBond) == Molecule.cBondTypeDown)) {
								refAtom[i] = mMol.getConnAtom(atom, j);
								refBond[i] = connBond;
								}
							else {
								otherAtom[i] = mMol.getConnAtom(atom, j);
								}
							}
						}
					if (refAtom[i] == -1)
						break;
					}
				if (refAtom[0] != -1 && refAtom[1] != -1) {
					// if both bonds connected to the double bond atoms have the same slash direction, we have Z
					// (assuming that the parent atom (i.e. bondAtom[0]) in both cases is the double bond atom)
					boolean isZ = mMol.getBondType(refBond[0]) == mMol.getBondType(refBond[1]);

					// We need to correct, because slash or backslash refer to the double bonded
					// atom and not to the double bond itself as explained in opensmiles.org:
					//     F/C=C/F and C(\F)=C/F are both E
					// bondAtom[0] is always the parent in graph to bondAtom[1]. We use this to correct:
					for (int i=0; i<2; i++)
						if (refAtom[i] == mMol.getBondAtom(0, refBond[i]))
							isZ = !isZ;

					// E/Z configuration in the StereoMolecule refer to those neighbors with
					// lower atom index. Thus, we adapt for this:
					for (int i=0; i<2; i++)
						if (otherAtom[i] != -1
						 && otherAtom[i] < refAtom[i])
							isZ = !isZ;

					mMol.setBondParity(bond, isZ ? Molecule.cBondParityZor2
												 : Molecule.cBondParityEor1, false);
					paritiesFound = true;
					}
				}
			}

		// convert temporary stereo bonds back to plain single bonds
		for (int bond=0; bond<mMol.getBonds(); bond++)
			if (mMol.getBondType(bond) == Molecule.cBondTypeUp
			 || mMol.getBondType(bond) == Molecule.cBondTypeDown)
				mMol.setBondType(bond, Molecule.cBondTypeSingle);

		return paritiesFound;
		}

	private class ParityNeighbour {
		int mAtom,mPosition;
		boolean mIsHydrogen;

		public ParityNeighbour(int atom, int position, boolean isHydrogen) {
			mAtom = atom;
			mPosition = position;
			mIsHydrogen = isHydrogen;
			}
		}

	private class THParity {
		int mCentralAtom,mImplicitHydrogen,mFromAtom;
		boolean mIsClockwise,mError;
		ArrayList<ParityNeighbour> mNeighbourList;

		/**
		 * Instantiates a new parity object during smiles traversal.
		 * @param centralAtom index of atoms processed
		 * @param fromAtom index of parent atom of centralAtom (-1 if centralAtom is first atom in smiles)
		 * @param implicitHydrogen Daylight syntax: hydrogen atoms defined within square bracket of other atom
		 * @param isClockwise true if central atom is marked with @@ rather than @
		 */
		public THParity(int centralAtom, int fromAtom, int implicitHydrogen, int hydrogenPosition, boolean isClockwise) {
			if (implicitHydrogen != 0 && implicitHydrogen != 1) {
				mError = true;
			}
			else {
				mCentralAtom = centralAtom;
				mFromAtom = fromAtom;
				mImplicitHydrogen = implicitHydrogen;
				mIsClockwise = isClockwise;
				mNeighbourList = new ArrayList<>();

				// If we have a fromAtom and we have an implicit hydrogen,
				// then make the implicit hydrogen a normal neighbour.
				if (fromAtom != -1 && implicitHydrogen == 1) {
					// We put it at the end of the atom list with MAX_VALUE
					addNeighbor(Integer.MAX_VALUE, hydrogenPosition, true);
					mImplicitHydrogen = 0;
				}
			}
		}

		/**
		 * Adds a currently traversed neighbor or ring closure to parity object,
		 * which belongs to the neighbor's parent atom.
		 * In case of a ring closure the bond closure digit's position in the smiles
		 * rather than the neighbor's position is the relevant position used for parity
		 * determination.
		 * We need to track the atom, because neighbors are not necessarily added in atom
		 * sequence (ring closure with connection back to stereo center).
		 * @param position
		 * @param isHydrogen
		 */
		public void addNeighbor(int atom, int position, boolean isHydrogen) {
			if (!mError) {
				if (mNeighbourList.size() == 4 || (mNeighbourList.size() == 3 && mFromAtom != -1)) {
					mError = true;
					return;
					}

				mNeighbourList.add(new ParityNeighbour(atom, position, isHydrogen));
				}
			}

		public int calculateParity(int[] handleHydrogenAtomMap) {
			if (mError)
				return Molecule.cAtomParityUnknown;

			// We need to translate smiles-parse-time atom indexes to those that the molecule
			// uses after calling handleHydrogens, which is called from ensureHelperArrays().
			if (mFromAtom != -1)
				mFromAtom = handleHydrogenAtomMap[mFromAtom];
			for (ParityNeighbour neighbour:mNeighbourList)
				if (neighbour.mAtom != Integer.MAX_VALUE)
					neighbour.mAtom = handleHydrogenAtomMap[neighbour.mAtom];

			if (mFromAtom == -1 && mImplicitHydrogen == 0) {
				// If we have no implicit hydrogen and the central atom is the first atom in the smiles,
				// then we assume that we have to take the first neighbor as from-atom (not described in Daylight theory manual).
				// Assumption: take the first neighbor as front atom, i.e. skip it when comparing positions
				int minPosition = Integer.MAX_VALUE;
				ParityNeighbour minNeighbour = null;
				for (ParityNeighbour neighbour:mNeighbourList) {
					if (minPosition > neighbour.mPosition) {
						minPosition = neighbour.mPosition;
						minNeighbour = neighbour;
						}
					}
				mFromAtom = minNeighbour.mAtom;
				mNeighbourList.remove(minNeighbour);
				}

			int totalNeighborCount = (mFromAtom == -1? 0 : 1) + mImplicitHydrogen + mNeighbourList.size();
			if (totalNeighborCount > 4 || totalNeighborCount < 3)
				return Molecule.cAtomParityUnknown;

			// We look from the hydrogen towards the central carbon if the fromAtom is a hydrogen or
			// if there is no fromAtom but the central atom has an implicit hydrogen.
			boolean fromAtomIsHydrogen = (mFromAtom == -1 && mImplicitHydrogen == 1)
					|| (mFromAtom != -1 && mMol.isSimpleHydrogen(mFromAtom));

			ParityNeighbour hydrogenNeighbour = null;
			for (ParityNeighbour neighbour:mNeighbourList) {
				if (neighbour.mIsHydrogen) {
					if (hydrogenNeighbour != null || fromAtomIsHydrogen)
						return Molecule.cAtomParityUnknown;
					hydrogenNeighbour = neighbour;
				}
			}

			// hydrogens are moved to the end of the atom list. If the hydrogen passes an odd number of
			// neighbor atoms on its way to the list end, we are effectively inverting the atom order.
			boolean isHydrogenTraversalInversion = false;
			if (hydrogenNeighbour != null)
				for (ParityNeighbour neighbour:mNeighbourList)
					if (neighbour != hydrogenNeighbour
					 && hydrogenNeighbour.mAtom < neighbour.mAtom)
						isHydrogenTraversalInversion = !isHydrogenTraversalInversion;

			// If fromAtom is not a hydrogen, we consider it moved to highest atom index,
			// because
			boolean fromAtomTraversalInversion = false;
			if (mFromAtom != -1 && !fromAtomIsHydrogen)
				for (ParityNeighbour neighbour:mNeighbourList)
					if (mFromAtom < neighbour.mAtom)
						fromAtomTraversalInversion = !fromAtomTraversalInversion;

			int parity = (mIsClockwise
					^ isInverseOrder()
					^ fromAtomTraversalInversion
					^ isHydrogenTraversalInversion) ?
					Molecule.cAtomParity2 : Molecule.cAtomParity1;
/*
System.out.println();
System.out.println("central:"+mCentralAtom+(mIsClockwise?" @@":" @")+" from:"
				+((mFromAtom == -1)?"none":Integer.toString(mFromAtom))+" with "+mImplicitHydrogen+" hydrogens");
System.out.print("neighbors: "+mNeighborAtom[0]+"("+mNeighborPosition[0]+(mNeighborIsHydrogen[0]?",H":",non-H")+")");
for (int i=1; i<mNeighborCount; i++)
	System.out.print(", "+mNeighborAtom[i]+"("+mNeighborPosition[i]+(mNeighborIsHydrogen[i]?",H":",non-H")+")");
System.out.println();
System.out.println("parity:"+parity);
*/
			return parity;
			}

		private boolean isInverseOrder() {
			boolean inversion = false;
			for (int i=1; i<mNeighbourList.size(); i++) {
				for (int j=0; j<i; j++) {
					if (mNeighbourList.get(j).mAtom > mNeighbourList.get(i).mAtom)
						inversion = !inversion;
					if (mNeighbourList.get(j).mPosition > mNeighbourList.get(i).mPosition)
						inversion = !inversion;
				}
			}
			return inversion;
		}
	}

	private static void testStereo() {
		final String[][] data = { { "F/C=C/I", "F/C=C/I" },
								  { "F/C=C\\I", "F/C=C\\I" },
								  { "C(=C/I)/F", "F/C=C\\I" },
								  { "[H]C(/F)=C/I", "F/C=C\\I" },
								  { "C(=C\\1)/I.F1", "F/C=C/I" },
								  { "C(=C1)/I.F/1", "F/C=C/I" },
								  { "C(=C\\F)/1.I1", "F/C=C/I" },
								  { "C(=C\\F)1.I\\1", "F/C=C/I" },
								  { "C\\1=C/I.F1", "F/C=C/I" },
								  { "C1=C/I.F/1", "F/C=C/I" },
								  { "C(=C\\1)/2.F1.I2", "F/C=C/I" },
								  { "C/2=C\\1.F1.I2", "F/C=C/I" },
								  { "C/1=C/C=C/F.I1", "F/C=C/C=C\\I" },
								  { "C1=C/C=C/F.I\\1", "F/C=C/C=C\\I" },
								  { "C(/I)=C/C=C/1.F1", "F/C=C/C=C\\I" },
								  { "C(/I)=C/C=C1.F\\1", "F/C=C/C=C\\I" },

								  { "[C@](Cl)(F)(I)1.Br1", "F[C@](Cl)(Br)I" },
								  { "Br[C@](Cl)(I)1.F1", "F[C@](Cl)(Br)I" },
								  { "[C@H](F)(I)1.Br1", "F[C@H](Br)I" },
								  { "Br[C@@H](F)1.I1", "F[C@H](Br)I" } };
		StereoMolecule mol = new StereoMolecule();
		for (String[] test:data) {
			try {
				new SmilesParser().parse(mol, test[0]);
				String smiles = new IsomericSmilesCreator(mol).getSmiles();
				System.out.print(test[0]+" "+smiles);
				if (!test[1].equals(smiles))
					System.out.println(" should be: "+test[1]);
				else
					System.out.println(" OK");
				}
			catch (Exception e) {
				if (!test[2].equals("error"))
					System.out.println("ERROR! "+test[1]+" smiles:"+test[0]+" exception:"+e.getMessage());
				}
			}
		}

	public static void main(String[] args) {
		testStereo();

		System.out.println("ID-code equivalence test:");
		final String[][] data = { {	"N[C@@]([H])(C)C(=O)O",	"S-alanine",		"gGX`BDdwMUM@@" },
								  { "N[C@@H](C)C(=O)O",		"S-alanine",		"gGX`BDdwMUM@@" },
								  { "N[C@H](C(=O)O)C",		"S-alanine",		"gGX`BDdwMUM@@" },
								  { "[H][C@](N)(C)C(=O)O",	"S-alanine",		"gGX`BDdwMUM@@" },
								  { "[C@H](N)(C)C(=O)O",	"S-alanine",		"gGX`BDdwMUM@@" },
								  { "N[C@]([H])(C)C(=O)O",	"R-alanine",		"gGX`BDdwMUL`@" },
								  { "N[C@H](C)C(=O)O",		"R-alanine",		"gGX`BDdwMUL`@" },
								  { "N[C@@H](C(=O)O)C",		"R-alanine",		"gGX`BDdwMUL`@" },
								  { "[H][C@@](N)(C)C(=O)O",	"R-alanine",		"gGX`BDdwMUL`@" },
								  { "[C@@H](N)(C)C(=O)O",	"R-alanine",		"gGX`BDdwMUL`@" },
								  { "C[C@H]1CCCCO1",		"S-Methyl-pyran",	"gOq@@eLm]UUH`@" },
								  { "O1CCCC[C@@H]1C",		"S-Methyl-pyran",	"gOq@@eLm]UUH`@" },
								  { "[C@H](F)(B)O",			"S-Methyl-oxetan",	"gCaDDICTBSURH@" },
								  { "C1CO[C@H]1C",			"S-Methyl-oxetan",	"gKQ@@eLmUTb@" },
								  { "C1CO[C@@H](C)1",		"S-Methyl-oxetan",	"gKQ@@eLmUTb@" },
								  { "[C@H]1(C)CCO1",		"S-Methyl-oxetan",	"gKQ@@eLmUTb@" },
								  { "[H][C@]1(C)CCO1",		"S-Methyl-oxetan",	"gKQ@@eLmUTb@" },
								  { "[H][C@@]1(CCO1)C",		"S-Methyl-oxetan",	"gKQ@@eLmUTb@" },
								  { "[C@@]1([H])(C)CCO1",	"S-Methyl-oxetan",	"gKQ@@eLmUTb@" },
								  { "[C@]1(C)([H])CCO1",	"S-Methyl-oxetan",	"gKQ@@eLmUTb@" },
								  { "C1[C@@H]2COC2=N1",		"oxetan-azetin",	"gGy@LDimDvfja`@" },
								  { "CC(C)[C@@]12C[C@@H]1[C@@H](C)C(=O)C2", "alpha-thujone", "dmLH@@RYe~IfyjjjkDaIh@" },
								  { "CN1CCC[C@H]1c2cccnc2",	"Nicotine",			"dcm@@@{IDeCEDUSh@UUECP@" },
								  { "CC[C@H](O1)CC[C@@]12CCCO2", "2S,5R-Chalcogran", "dmLD@@qJZY|fFZjjjdbH`@" },
								  { "CCCC",					"butane",			"gC`@Dij@@" },
								  { "C1C.CC1",				"butane",			"gC`@Dij@@" },
								  { "[CH3][CH2][CH2][CH3]",	"butane",			"gC`@Dij@@" },
								  { "C-C-C-C",				"butane",			"gC`@Dij@@" },
								  { "C12.C1.CC2",			"butane",			"gC`@Dij@@" },
								  { "[Na+].[Cl-]",			"NaCl",				"eDARHm@zd@@" },
								  { "[Na+]-[Cl-]",			"NaCl",				"error" },
								  { "[Na+]1.[Cl-]1",		"NaCl",				"error" },
								  { "c1ccccc1",				"benzene",			"gFp@DiTt@@@" },
								  { "C1=C-C=C-C=C1",		"benzene",			"gFp@DiTt@@@" },
								  { "C1:C:C:C:C:C:1",		"benzene",			"gFp@DiTt@@@" },
								  { "c1ccncc1",				"pyridine",			"gFx@@eJf`@@@" },
								  { "[nH]1cccc1",			"pyrrole",			"gKX@@eKcRp@" },
								  { "N1C=C-C=C1",			"pyrrole",			"gKX@@eKcRp@" },
								  { "[H]n1cccc1",			"pyrrole",			"gKX@@eKcRp@" },
								  { "[H]n1cccc1",			"pyrrole",			"gKX@@eKcRp@" },
								  { "c1cncc1",				"pyrrole no [nH]",	"error" },
								  { "[13CH4]",				"C13-methane",		"fH@FJp@" },
								  { "[35ClH]",				"35-chlorane",		"fHdP@qX`" },
								  { "[35Cl-]",				"35-chloride",		"fHtPxAbq@" },
								  { "[Na+].[O-]c1ccccc1",	"Na-phenolate",		"daxHaHCPBXyAYUn`@@@" },
								  { "c1cc([O-].[Na+])ccc1",	"Na-phenolate",		"daxHaHCPBXyAYUn`@@@" },
								  { "C[C@@](C)(O1)C[C@@H](O)[C@@]1(O2)[C@@H](C)[C@@H]3CC=C4[C@]3(C2)C(=O)C[C@H]5[C@H]4CC[C@@H](C6)[C@]5(C)Cc(n7)c6nc(C[C@@]89(C))c7C[C@@H]8CC[C@@H]%10[C@@H]9C[C@@H](O)[C@@]%11(C)C%10=C[C@H](O%12)[C@]%11(O)[C@H](C)[C@]%12(O%13)[C@H](O)C[C@@]%13(C)CO",
									"Cephalostatin-1",
									"gdKe@h@@K`H@XjKHuYlnoP\\bbdRbbVTLbTrJbRaQRRRbTJTRTrfrfTTOBPHtFODPhLNSMdIERYJmShLfs]aqy|uUMUUUUUUE@UUUUMUUUUUUTQUUTPR`nDdQQKB|RIFbiQeARuQt`rSSMNtGS\\ct@@" },
									};

		StereoMolecule mol = new StereoMolecule();
		for (String[] test:data) {
			try {
				new SmilesParser().parse(mol, test[0]);
				String idcode = new Canonizer(mol).getIDCode();
				if (test[2].equals("error"))
					System.out.println("Should create error! "+test[1]+" smiles:"+test[0]+" idcode:"+idcode);
				else if (!test[2].equals(idcode))
					System.out.println("ERROR! "+test[1]+" smiles:"+test[0]+" is:"+idcode+" must:"+test[2]);
				}
			catch (Exception e) {
				if (!test[2].equals("error"))
					System.out.println("ERROR! "+test[1]+" smiles:"+test[0]+" exception:"+e.getMessage());
				}
			}
		}
	}