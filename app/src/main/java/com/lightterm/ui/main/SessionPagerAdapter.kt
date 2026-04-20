package com.lightterm.ui.main

import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.lightterm.core.session.SessionTabUiModel
import com.lightterm.ui.session.SessionFragment

class SessionPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    private var tabs: List<SessionTabUiModel> = emptyList()

    override fun getItemCount(): Int = tabs.size

    override fun createFragment(position: Int) = SessionFragment.newInstance(
        sessionId = tabs[position].sessionId,
    )

    override fun getItemId(position: Int): Long = tabs[position].sessionId.hashCode().toLong()

    override fun containsItem(itemId: Long): Boolean {
        return tabs.any { it.sessionId.hashCode().toLong() == itemId }
    }

    fun submitTabs(newTabs: List<SessionTabUiModel>) {
        tabs = newTabs
        notifyDataSetChanged()
    }

    fun getDisplayTitle(position: Int): String = tabs.getOrNull(position)?.title.orEmpty()

    fun getTab(position: Int): SessionTabUiModel? = tabs.getOrNull(position)
}
