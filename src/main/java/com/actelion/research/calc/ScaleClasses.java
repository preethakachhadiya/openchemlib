package com.actelion.research.calc;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Modest v. Korff
 * Idorsia Pharmaceuticals Ltd.
 * 18.01.2022 Start implementation
 **/
public class ScaleClasses {

	private List<Limit> liLimit;

	public ScaleClasses(){
		liLimit = new ArrayList<Limit>();
	}


	public void add(double inLow, double inHigh, double scLow, double scHigh){
		Limit lim = new Limit(inLow, inHigh, scLow, scHigh);
		liLimit.add(lim);
	}

	public double scale(double v){
		double sc = 0;

		for (Limit limit : liLimit) {
			if(limit.isInRange(v)){
				sc = limit.scale(v);
				break;
			}
		}

		return sc;
	}

	public float scale(float v){
		float sc = 0;

		for (Limit limit : liLimit) {
			if(limit.isInRange(v)){
				sc = (float)limit.scale(v);
				break;
			}
		}

		return sc;
	}

	public static void main(String [] args){
		int n = 20;

		NumberFormat nf = new DecimalFormat("0.000");

		ScaleClasses scw = new ScaleClasses();
		scw.add(0.0, 0.25, 0, 0.45);
		scw.add(0.25, 0.75, 0.45, 0.55);
		scw.add(0.75, 1.0, 0.55, 1.0);


		Random rnd = new Random();

		List<Double> li = new ArrayList<Double>();
		li.add(0.0);
		li.add(0.1);
		li.add(0.25);
		li.add(0.5);
		li.add(0.6);
		li.add(0.7);
		li.add(0.8);
		li.add(0.9);
		li.add(0.91);
		li.add(1.0);


		for (Double ddd : li) {

			double sc = scw.scale(ddd);
			System.out.println("val " + nf.format(ddd) + " scaled " + nf.format(sc));
		}


//		for (int i = 0; i < n; i++) {
//			double v = rnd.nextDouble();
//			double sc = scw.scale(v);
//			System.out.println("val " + nf.format(v) + " scaled " + nf.format(sc));
//		}





	}

	private static class Limit {

		private double mInHigh;
		private double mInLow;

		private double mScHigh;
		private double mScLow;

		private double mDeltaIn;
		private double mDeltaSc;
		private double mScale;


		public Limit(double inLow, double inHigh, double scLow, double scHigh){
			this.mInHigh = inHigh;
			this.mScHigh = scHigh;
			this.mInLow = inLow;
			this.mScLow = scLow;

			init();
		}

		private void init(){
			mDeltaIn = mInHigh-mInLow;
			mDeltaSc = mScHigh-mScLow;

			mScale = mDeltaSc / mDeltaIn ;
		}


		public double scale(double v){
			double sc = 0;

			double vn = v-mInLow;

			sc = vn * mScale + mScLow;

			return sc;
		}

		public boolean isInRange(double v){
			if(v>=mInLow && v <= mInHigh)
				return true;
			else return false;
		}

	}
}
