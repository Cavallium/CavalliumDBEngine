package it.cavallium.dbengine.lucene.analyzer;

import it.cavallium.dbengine.lucene.LuceneUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.KeywordTokenizer;
import org.apache.lucene.analysis.ngram.NGramTokenFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

public class NCharGramAnalyzer extends Analyzer {

	private final boolean words;
	private final int minGram;
	private final int maxGram;

	public NCharGramAnalyzer(boolean words, int minGram, int maxGram) {
		this.words = words;
		this.minGram = minGram;
		this.maxGram = maxGram;
	}

	@Override
	protected TokenStreamComponents createComponents(final String fieldName) {
		Tokenizer tokenizer;
		TokenStream tokenStream;
		if (words) {
			tokenizer = new StandardTokenizer();
		} else {
			tokenizer = new KeywordTokenizer();
		}
		tokenStream = tokenizer;
		tokenStream = LuceneUtils.newCommonFilter(tokenStream, words);
		tokenStream = new NGramTokenFilter(tokenStream, minGram, maxGram, false);

		return new TokenStreamComponents(tokenizer, tokenStream);
	}

	@Override
	protected TokenStream normalize(String fieldName, TokenStream in) {
		TokenStream tokenStream = in;
		tokenStream = LuceneUtils.newCommonNormalizer(tokenStream);
		return tokenStream;
	}
}
