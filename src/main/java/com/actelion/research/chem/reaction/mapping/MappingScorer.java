package com.actelion.research.chem.reaction.mapping;

import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.StereoMolecule;

import java.util.Arrays;

public class MappingScorer {
	// Simple scoring just adds bond order changes (delocalized=1.5) of every added, broken, or changed bond.
	// Otherwise, added and broken bonds score with higher penalties and simple chemical rules slighly adjust scoring,
	// e.g. prefer changes of C-hetero to C-C bonds.
	private static final boolean SCORE_SIMPLE = false;
	private static final boolean SCORE_HYDROGEN = false;

	private StereoMolecule mReactant,mProduct;

	/**
	 * Instantiates a mapping scorer that judges the quality of a mapping by adding penalties for every bond
	 * being broken, created, or changed. In principle the panelty for any created or broken bond is 2.0,
	 * and for any changed bond order is 1.0. A change from/to delocalized to/from single or double is considered
	 * a change. Broken or created bonds at typical break locations, e.g. ester cleavage, get slightly lower
	 * penalties than 2.0. Changes of implicit hydrogen counts contribute with a factor of 2.0.
	 * @param reactant
	 * @param product
	 */
	public MappingScorer(StereoMolecule reactant, StereoMolecule product) {
		mReactant = reactant;
		mProduct = product;
		}

	public int[] createReactantToProductAtomMap(int[] reactantMapNo, int[] productMapNo) {
		int[] mapNoToProductAtom = new int[mReactant.getAtoms()+1];
		Arrays.fill(mapNoToProductAtom, -1);
		for (int atom=0; atom<productMapNo.length; atom++)
			if (productMapNo[atom] != 0)
				mapNoToProductAtom[productMapNo[atom]] = atom;
		int[] reactantToProductAtom = new int[mReactant.getAtoms()];
		Arrays.fill(reactantToProductAtom, -1);
		for (int atom=0; atom<reactantMapNo.length; atom++)
			reactantToProductAtom[atom] = mapNoToProductAtom[reactantMapNo[atom]];
		return reactantToProductAtom;
		}

	/**
	 * @param reactantToProductAtom
	 * @return the mapping score considering all (score is negative value; 0: no bond changes)
	 */
	public float scoreMapping(int[] reactantToProductAtom) {
		float penalty = 0;

		// For all atoms assigned in reactantToProductAtom that undergo any bond changes,
		// we add/remove fractional bond orders for new/broken bonds and
		// we add fractional bond order changes of changed bonds
		// to reflect the corresponding change in implicit hydrogen bond counts.
		float[] hydrogenBondPenalty = SCORE_HYDROGEN ? new float[mProduct.getAtoms()] : null;

		boolean[] isAssignedProductAtom = new boolean[mProduct.getAtoms()];
		for (int atom:reactantToProductAtom)
			if (atom != -1)
				isAssignedProductAtom[atom] = true;

		boolean[] productBondHandled = new boolean[mProduct.getBonds()];
		for (int rBond=0; rBond<mReactant.getBonds(); rBond++) {
			int rAtom1 = mReactant.getBondAtom(0, rBond);
			int rAtom2 = mReactant.getBondAtom(1, rBond);
			int pAtom1 = reactantToProductAtom[rAtom1];
			int pAtom2 = reactantToProductAtom[rAtom2];

			float rBondOrder = getFractionalBondOrder(mReactant, rBond);

			if (pAtom1 == -1 || pAtom2 == -1) {
				if (pAtom1 != -1 || pAtom2 != -1)
					penalty += getBondCreateOrBreakPenalty(mReactant, rBond);

				if (SCORE_HYDROGEN) {
					if (pAtom1 != -1)
						hydrogenBondPenalty[pAtom1] += rBondOrder;
					if (pAtom2 != -1)
						hydrogenBondPenalty[pAtom2] += rBondOrder;
					}
				continue;
				}

			int pBond = mProduct.getBond(pAtom1, pAtom2);
			if (pBond == -1) {
				penalty += getBondCreateOrBreakPenalty(mReactant, rBond);

				if (SCORE_HYDROGEN) {
					hydrogenBondPenalty[pAtom1] += rBondOrder;
					hydrogenBondPenalty[pAtom2] += rBondOrder;
					}

				continue;
				}

			if (SCORE_HYDROGEN) {
				float bondOrderChange = rBondOrder - getFractionalBondOrder(mProduct, pBond);
				hydrogenBondPenalty[pAtom1] += bondOrderChange;
				hydrogenBondPenalty[pAtom2] += bondOrderChange;
				}

			productBondHandled[pBond] = true;
			penalty += getBondChangePenalty(rBond, pBond);
			}

		for (int pBond=0; pBond<mProduct.getBonds(); pBond++) {
			if (!productBondHandled[pBond]) {
				penalty += getBondCreateOrBreakPenalty(mProduct, pBond);

				if (SCORE_HYDROGEN) {
					float pBondOrder = getFractionalBondOrder(mProduct, pBond);
					int pAtom1 = mProduct.getBondAtom(0, pBond);
					int pAtom2 = mProduct.getBondAtom(1, pBond);
					if (isAssignedProductAtom[pAtom1])
						hydrogenBondPenalty[pAtom1] -= pBondOrder;
					if (isAssignedProductAtom[pAtom2])
						hydrogenBondPenalty[pAtom2] -= pBondOrder;
					}
				}
			}

		for (int rAtom=0; rAtom<mReactant.getAtoms(); rAtom++)
			if (mReactant.getAtomParity(rAtom) != Molecule.cAtomParityNone)
				penalty += getParityInversionPenalty(rAtom, reactantToProductAtom);

		// TODO adjust factor or optimize scoring in general
		if (SCORE_HYDROGEN)
			for (int pAtom=0; pAtom<mProduct.getAtoms(); pAtom++)
				penalty += 0.5f * Math.abs(hydrogenBondPenalty[pAtom]);

		return -penalty;
		}

	/**
	 * penalty for a created or broken bond
	 * @param mol
	 * @param bond
	 * @return
	 */
	private float getBondCreateOrBreakPenalty(StereoMolecule mol, int bond) {
		if (SCORE_SIMPLE)
			return (float)mol.getBondOrder(bond);

		int atom1 = mol.getBondAtom(0, bond);
		int atom2 = mol.getBondAtom(1, bond);
		boolean isHetero1 = mol.isElectronegative(atom1);
		boolean isHetero2 = mol.isElectronegative(atom2);

		if (!isHetero1 && !isHetero2)
			return mol.isAromaticBond(bond) ? 2.1f : 1.9f + (float)mol.getBondOrder(bond) / 10f;

		if (isHetero1 && isHetero2)    // e.g. m-CPBA
			return 1.7f;

		if ((isHetero1 && mol.isMetalAtom(atom2))
		 || (isHetero2 && mol.isMetalAtom(atom1)))
			return 1.7f;

		if ((isHetero1 && SimilarityGraphBasedReactionMapper.hasOxo(mol, atom2, atom1))
		 || (isHetero2 && SimilarityGraphBasedReactionMapper.hasOxo(mol, atom1, atom2)))
			return 1.8f;

		if ((isHetero1 && SimilarityGraphBasedReactionMapper.hasNonCarbonNeighbour(mol, atom2, atom1))
		 || (isHetero2 && SimilarityGraphBasedReactionMapper.hasNonCarbonNeighbour(mol, atom1, atom2)))
			return 1.85f;

		if ((isHetero1 && mol.isAromaticAtom(atom2))
		 || (isHetero2 && mol.isAromaticAtom(atom1)))   // phenol-oxygen stays in arom-nonArom-ether formation
			return 1.95f;

		// any other hetero-to-carbon bond
		return 1.9f;
		}


	private float getFractionalBondOrder(StereoMolecule mol, int bond) {
		return mol. isDelocalizedBond(bond) ? 1.5f : mol.getBondOrder(bond);
		}

	private int getBondType(StereoMolecule mol, int bond) {
		if (mol.isDelocalizedBond(bond))
			return 0;
		return mol.getBondTypeSimple(bond);
		}

	/**
	 * penalty for a changed bond
	 * @param rBond
	 * @param pBond
	 * @return
	 */
	private float getBondChangePenalty(int rBond, int pBond) {
		if (SCORE_SIMPLE)
			return Math.abs(getFractionalBondOrder(mReactant, rBond) - getFractionalBondOrder(mProduct, pBond));

		return getBondType(mReactant, rBond) == getBondType(mProduct, pBond) ? 0f : 1f;
		}

	private float getParityInversionPenalty(int reactantAtom, int[] reactantToProductAtom) {
		// if we change a stereo center's parity, we must have broken or formed bonds
		int productAtom = reactantToProductAtom[reactantAtom];
		if (productAtom != -1
		 && mProduct.getAtomParity(productAtom) != Molecule.cAtomParityNone
		 && hasSameNeighbours(reactantAtom, productAtom, reactantToProductAtom)) {
			int reactantParity = mReactant.getAtomParity(reactantAtom);
			int productParity = mProduct.getAtomParity(productAtom);
			if (reactantParity == Molecule.cAtomParityUnknown) {
				if (productParity == Molecule.cAtomParity1
				 || productParity == Molecule.cAtomParity2)
					return SCORE_SIMPLE ? 4.0f : 5.0f;
							// one broken and one formed bond plus additional panelty!
							// must be more expensive than Mitsunobu, which itself must be more expensive than simple esterification (one broken and one formed bond)
				}
			else {
				if (productParity == Molecule.cAtomParityUnknown
				 || isTHParityInversion(reactantAtom, reactantToProductAtom) == (reactantParity == productParity))
					return SCORE_SIMPLE ? 4.0f : 5.0f;
				}
			}
		return 0f;
		}

	private boolean isTHParityInversion(int reactantAtom, int[] reactantToProductAtom) {
		boolean inversion = false;
		if (mReactant.getAtomPi(reactantAtom) == 0) {
			for (int i=1; i<mReactant.getConnAtoms(reactantAtom); i++) {
				for (int j=0; j<i; j++) {
					int connAtom1 = mReactant.getConnAtom(reactantAtom,i);
					int connAtom2 = mReactant.getConnAtom(reactantAtom,j);
					int connProductAtom1 = reactantToProductAtom[connAtom1];
					int connProductAtom2 = reactantToProductAtom[connAtom2];
					if (connProductAtom1 != -1 && connProductAtom2 != -1
					 && ((connProductAtom1 > connProductAtom2) ^ (connAtom1 > connAtom2)))
						inversion = !inversion;
					}
				}
			}
/*		else {  // no allene parities for now
			for (int i=0; i<mReactant.getConnAtoms(reactantAtom); i++) {
				int connAtom = mReactant.getConnAtom(reactantAtom, i);
				int neighbours = 0;
				int[] neighbour = new int[3];
				int[] neighbourInProduct = new int[3];
				for (int j=0; j<mReactant.getConnAtoms(connAtom); j++) {
					neighbour[neighbours] = mReactant.getConnAtom(connAtom, j);
					neighbourInProduct[neighbours] = reactantToProductAtom[neighbour[neighbours]];
					if (neighbour[neighbours] != reactantAtom)
						neighbours++;
					}
				if (neighbours == 2
				 && neighbourInProduct[0] != -1 && neighbourInProduct[1] != -1
				 && ((neighbourInProduct[0] > neighbourInProduct[1])
					^(neighbour[0] > neighbour[1])))
					inversion = !inversion;
				}
			}*/
		return inversion;
		}

	private boolean hasSameNeighbours(int reactantAtom, int productAtom, int[] reactantToProductAtom) {
		if (mReactant.getConnAtoms(reactantAtom) != mProduct.getConnAtoms(productAtom))
			return false;

		for (int i=0; i<mReactant.getConnAtoms(reactantAtom); i++) {
			int rConn = mReactant.getConnAtom(reactantAtom, i);
			boolean found = false;
			for (int j=0; j<mProduct.getConnAtoms(productAtom); j++) {
				if (reactantToProductAtom[rConn] == mProduct.getConnAtom(productAtom, j)) {
					found = true;
					break;
					}
				}
			if (!found)
				return false;
			}

		return true;
		}
	}
