/*
 * Copyright 2023-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.reader.markdown;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Reads the given Markdown resource and groups headers, paragraphs, or text divided by
 * horizontal lines (depending on the
 * {@link MarkdownDocumentReaderConfig#horizontalRuleCreateDocument} configuration) into
 * {@link Document}s.
 * <p>
 * 默认的切分规则是：
 * <p>
 * 标题会变成 metadata；
 * <p>
 * 段落变成 Document.text；
 * <p>
 * 代码块会单独成为一个 Document
 * <p>
 * blockquote 引用块成为一个 Document
 *
 * @author Piotr Olaszewski
 */
public class MarkdownDocumentReader implements DocumentReader {

	/**
	 * The resources read by this document reader.
	 */
	private final Resource[] markdownResources;

	/**
	 * Configuration to a parsing process.
	 */
	private final MarkdownDocumentReaderConfig config;

	/**
	 * Markdown parser.
	 * <p>
	 * 第三方框架提供的解析器支持
	 */
	private final Parser parser;

	/**
	 * Create a new {@link MarkdownDocumentReader} instance.
	 * <p>
	 * 通过 PathMatchingResourcePatternResolver 解析，所以有可能读取到多个 md
	 * <p>
	 * 从代码看，也不强求后缀名是 md
	 *
	 * @param markdownResources the resources to read, will be resolved via
	 *                          {@link PathMatchingResourcePatternResolver}
	 */
	public MarkdownDocumentReader(String markdownResources) {
		this(markdownResources, MarkdownDocumentReaderConfig.defaultConfig());
	}

	/**
	 * Create a new {@link MarkdownDocumentReader} instance.
	 *
	 * @param markdownResources the resources to read, will be resolved via
	 *                          {@link PathMatchingResourcePatternResolver}
	 * @param config            the configuration to use
	 */
	public MarkdownDocumentReader(String markdownResources, MarkdownDocumentReaderConfig config) {
		this(resolveResources(markdownResources), config);
	}

	/**
	 * Create a new {@link MarkdownDocumentReader} instance using a single
	 * {@link Resource}.
	 *
	 * @param markdownResource the resource to read
	 */
	public MarkdownDocumentReader(Resource markdownResource, MarkdownDocumentReaderConfig config) {
		this(List.of(markdownResource), config);
	}

	/**
	 * Create a new {@link MarkdownDocumentReader} instance using already resolved
	 * {@link Resource resources}.
	 *
	 * @param markdownResources the resources to read
	 */
	public MarkdownDocumentReader(List<Resource> markdownResources, MarkdownDocumentReaderConfig config) {
		this.markdownResources = markdownResources.toArray(new Resource[0]);
		this.config = config;
		this.parser = Parser.builder().build();
	}

	/**
	 * 工具方法，将字符串资源表现形式解析为 N 个 Resource 对象
	 */
	private static List<Resource> resolveResources(String markdownResources) {
		try {
			return List.of(new PathMatchingResourcePatternResolver().getResources(markdownResources));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Extracts and returns a list of documents from the resource.
	 *
	 * @return List of extracted {@link Document}
	 */
	@Override
	public List<Document> get() {
		List<Document> documents = new ArrayList<>();
		for (Resource markdownResource : this.markdownResources) {
			// 创建一个内部类，叫文档拜访器(org.commonmark.node.Visitor)，估计可以递归遍历 markdown 最后得到一个 documents 吧
			DocumentVisitor documentVisitor = new DocumentVisitor(this.config);
			try (var input = markdownResource.getInputStream()) {
				Node node = this.parser.parseReader(new InputStreamReader(input));

				// 遍历，org.commonmark.node.Visitor 会在每个插入点执行逻辑
				node.accept(documentVisitor);

				documents.addAll(documentVisitor.getDocuments());
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return documents;
	}

	/**
	 * A convenient class for visiting handled nodes in the Markdown document.
	 */
	static class DocumentVisitor extends AbstractVisitor {

		private final List<Document> documents = new ArrayList<>();

		private final List<String> currentParagraphs = new ArrayList<>();

		private final MarkdownDocumentReaderConfig config;

		private Document.Builder currentDocumentBuilder;

		DocumentVisitor(MarkdownDocumentReaderConfig config) {
			this.config = config;
		}

		/**
		 * Visits the document node and initializes the current document builder.
		 * <p>
		 * 整个 markdown 的根节点，初始化 currentDocumentBuilder(Document.Builder)
		 */
		@Override
		public void visit(org.commonmark.node.Document document) {
			this.currentDocumentBuilder = Document.builder();
			super.visit(document);
		}

		/**
		 * #/## 标题
		 * @param heading
		 */
		@Override
		public void visit(Heading heading) {
			buildAndFlush();
			super.visit(heading);
		}

		@Override
		public void visit(ThematicBreak thematicBreak) {
			if (this.config.horizontalRuleCreateDocument) {
				buildAndFlush();
			}
			super.visit(thematicBreak);
		}

		@Override
		public void visit(SoftLineBreak softLineBreak) {
			translateLineBreakToSpace();
			super.visit(softLineBreak);
		}

		@Override
		public void visit(HardLineBreak hardLineBreak) {
			translateLineBreakToSpace();
			super.visit(hardLineBreak);
		}

		@Override
		public void visit(ListItem listItem) {
			translateLineBreakToSpace();
			super.visit(listItem);
		}

		@Override
		public void visit(BlockQuote blockQuote) {
			// 如果不把引用块并入前面的正文，那么正文到此为止
			if (!this.config.includeBlockquote) {
				buildAndFlush();
			}

			// 如果存在段落，那么增加一个空格
			translateLineBreakToSpace();

			// 记录 metadata
			this.currentDocumentBuilder.metadata("category", "blockquote");
			super.visit(blockQuote);
		}

		@Override
		public void visit(Code code) {
			this.currentParagraphs.add(code.getLiteral());
			this.currentDocumentBuilder.metadata("category", "code_inline");
			super.visit(code);
		}

		@Override
		public void visit(FencedCodeBlock fencedCodeBlock) {
			// 如果不把代码块并入前面，那么就到此位置构建 Document
			if (!this.config.includeCodeBlock) {
				buildAndFlush();
			}

			translateLineBreakToSpace();
			this.currentParagraphs.add(fencedCodeBlock.getLiteral());
			this.currentDocumentBuilder.metadata("category", "code_block");
			this.currentDocumentBuilder.metadata("lang", fencedCodeBlock.getInfo());

			buildAndFlush();

			super.visit(fencedCodeBlock);
		}

		@Override
		public void visit(Text text) {
			// 一般 Heading 之后紧跟着 Text 可以读取到标题文字
			if (text.getParent() instanceof Heading heading) {
				// 添加了两个 metadata category + title
				this.currentDocumentBuilder.metadata("category", "header_%d".formatted(heading.getLevel()))
						.metadata("title", text.getLiteral());
			} else {
				this.currentParagraphs.add(text.getLiteral());
			}

			super.visit(text);
		}

		public List<Document> getDocuments() {
			buildAndFlush();

			return this.documents;
		}

		private void buildAndFlush() {
			if (!this.currentParagraphs.isEmpty()) {
				String content = String.join("", this.currentParagraphs);

				Document.Builder builder = this.currentDocumentBuilder.text(content);

				this.config.additionalMetadata.forEach(builder::metadata);

				Document document = builder.build();

				this.documents.add(document);

				this.currentParagraphs.clear();
			}

			// 重新初始化一个 Document
			this.currentDocumentBuilder = Document.builder();
		}

		private void translateLineBreakToSpace() {
			if (!this.currentParagraphs.isEmpty()) {
				this.currentParagraphs.add(" ");
			}
		}

	}

}
