package com.droidfeed.ui.module.feed

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import androidx.paging.PagedList
import androidx.recyclerview.widget.RecyclerView
import com.droidfeed.R
import com.droidfeed.data.DataStatus
import com.droidfeed.data.model.Post
import com.droidfeed.databinding.FragmentFeedBinding
import com.droidfeed.ui.adapter.BaseUiModelAlias
import com.droidfeed.ui.adapter.UiModelPaginatedAdapter
import com.droidfeed.ui.common.BaseFragment
import com.droidfeed.ui.common.WrapContentLinearLayoutManager
import com.droidfeed.ui.module.main.MainViewModel
import com.droidfeed.util.AppRateHelper
import com.droidfeed.util.CustomTab
import com.droidfeed.util.event.EventObserver
import com.droidfeed.util.extention.isOnline
import com.droidfeed.util.shareCount
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.fragment_feed.*
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import javax.inject.Inject
import kotlin.math.absoluteValue

class FeedFragment : BaseFragment("feed") {

    private lateinit var viewModel: FeedViewModel
    private lateinit var mainViewModel: MainViewModel
    private lateinit var binding: FragmentFeedBinding
    private lateinit var adapter: UiModelPaginatedAdapter

    @Inject
    lateinit var customTab: CustomTab

    @Inject
    lateinit var sharedPrefs: SharedPreferences

    @Inject
    lateinit var appRateHelper: AppRateHelper

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        binding = FragmentFeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        if (!::viewModel.isInitialized) {
            viewModel = ViewModelProviders
                .of(this, viewModelFactory)
                .get(FeedViewModel::class.java)
        }
        if (!::mainViewModel.isInitialized) {
            mainViewModel = ViewModelProviders
                .of(activity!!)
                .get(MainViewModel::class.java)
        }

        init()
        initDataObservables()
    }

    private fun init() {
        if (!::adapter.isInitialized) {
            adapter = UiModelPaginatedAdapter()
        }

        binding.apply {
            val layoutManager = activity?.let { WrapContentLinearLayoutManager(it) }
            newsRecyclerView.layoutManager = layoutManager

            var scrolledAmount = 0
            newsRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    scrolledAmount += dy.absoluteValue

                    if (scrolledAmount > 200) {
                        mainViewModel.onScrolledEnough()
                        scrolledAmount = 0
                    }

                    GlobalScope.launch {
                        delay(200)
                        scrolledAmount = 0
                    }
                }
            })

            (newsRecyclerView.itemAnimator as androidx.recyclerview.widget.DefaultItemAnimator)
                .supportsChangeAnimations = false
            newsRecyclerView.swapAdapter(adapter, true)

            swipeRefreshArticles.setOnRefreshListener {
                if (context?.isOnline() == true) {
                    this@FeedFragment.viewModel.refresh()
                } else {
                    swipeRefreshArticles.isRefreshing = false
                    Snackbar.make(
                        swipeRefreshArticles,
                        R.string.info_no_internet,
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun initDataObservables() {
        viewModel.posts.observe(this, Observer { pagedList ->
            pagedList?.let { list ->
                adapter.submitList(list as PagedList<BaseUiModelAlias>)

                binding.containerEmptyBookmark.visibility =
                        if (list.size == 0 && !swipeRefreshArticles.isEnabled) {
                            View.VISIBLE
                        } else {
                            View.GONE
                        }
            }
        })

        viewModel.networkState.observe(this, Observer {
            when (it) {
                DataStatus.Success -> {
                    if (swipeRefreshArticles.isRefreshing) {
                        swipeRefreshArticles.isRefreshing = false
                    }
                    binding.progressBar.visibility = View.GONE
                }
                DataStatus.Loading -> {
                    if (adapter.itemCount == 0) {
                        binding.progressBar.visibility = View.VISIBLE
                    }
                }
                DataStatus.Error<Any>() -> {
                    if (swipeRefreshArticles.isRefreshing) {
                        swipeRefreshArticles.isRefreshing = false
                    }
                    binding.progressBar.visibility = View.GONE
                }
            }
        })

        viewModel.postBookmarkEvent.observe(this, Observer {
            appRateHelper.checkAppRatePrompt(binding.root)
            analytics.logBookmark(true)
        })

        viewModel.postOpenDetail.observe(this, Observer {
            it?.let { post -> customTab.showTab(post.link) }
            analytics.logPostClick()
        })

        viewModel.postShareEvent.observe(this, Observer {
            sharedPrefs.shareCount += 1
            startActivityForResult(it, REQUEST_CODE_SHARE)
            analytics.logShare("post")
        })

        viewModel.postUnBookmarkEvent.observe(this, Observer { article ->
            article?.let {
                if (viewModel.feedType.value == FeedType.BOOKMARKS) {
                    showBookmarkUndoSnackbar(it)
                }
                analytics.logBookmark(false)
            }
        })

        viewModel.sources.observe(this, Observer { sources ->
            val activeSource = sources?.firstOrNull { it.isActive }
            binding.containerEmptySource.visibility = if (activeSource != null) {
                View.GONE
            } else {
                View.VISIBLE
            }

            // initial refresh
            if (adapter.itemCount == 0) {
                viewModel.refresh()
            }
        })

        mainViewModel.bookmarksEvent.observe(this, EventObserver { isEnabled ->
            val feedType = when {
                isEnabled -> {
                    FeedType.BOOKMARKS
                }
                else -> FeedType.POSTS
            }
            viewModel.setFeedType(feedType)
            swipeRefreshArticles.isEnabled = !isEnabled
        })

        viewModel.setFeedType(FeedType.POSTS)
    }

    private fun showBookmarkUndoSnackbar(post: Post) {
        Snackbar.make(
            binding.root,
            R.string.info_bookmark_removed,
            Snackbar.LENGTH_LONG
        )
            .setActionTextColor(Color.YELLOW)
            .setAction(R.string.undo) { viewModel.toggleBookmark(post) }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_SHARE -> appRateHelper.checkAppRatePrompt(binding.root)
        }
    }

    internal fun scrollToTop() {
        binding.newsRecyclerView.smoothScrollToPosition(0)
    }

    companion object {
        private const val REQUEST_CODE_SHARE = 4122
    }
}
