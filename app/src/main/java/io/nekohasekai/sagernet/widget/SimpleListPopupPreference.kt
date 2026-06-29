/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026  dyhkwong                                               *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.content.ContextCompat
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px


open class SimpleListPopupPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ListPreference(context, attrs) {

    private lateinit var anchorView: View
    private lateinit var mAdapter: SimpleMenuAdapter
    private lateinit var popupWindow: ListPopupWindow

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        anchorView = holder.itemView
    }

    override fun onClick() {
        showPopup()
    }

    private fun showPopup() {
        val selectedIndex = findIndexOfValue(value)
        mAdapter = SimpleMenuAdapter(context, entries, selectedIndex)

        popupWindow = ListPopupWindow(context).apply {
            setAdapter(this@SimpleListPopupPreference.mAdapter)
            anchorView = this@SimpleListPopupPreference.anchorView
            width = measureContentWidth() + dp2px(2)
            height = ListPopupWindow.WRAP_CONTENT
            isModal = true
            setOnItemClickListener { _, _, position, _ ->
                val newValue = entryValues[position].toString()
                if (callChangeListener(newValue)) {
                    value = newValue
                }
                mAdapter.setSelectedItemPosition(position)
                dismiss()
            }
        }

        popupWindow.show()
    }

    private fun measureContentWidth(): Int {
        var mMeasureParent: ViewGroup? = null
        var maxWidth = 0
        var itemView: View? = null
        var itemType = 0
        val widthMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val heightMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        for (i in 0..<mAdapter.count) {
            val positionType: Int = mAdapter.getItemViewType(i)
            if (positionType != itemType) {
                itemType = positionType
                itemView = null
            }
            if (mMeasureParent == null) {
                mMeasureParent = FrameLayout(context)
            }
            itemView = mAdapter.getView(i, itemView, mMeasureParent)
            itemView.measure(widthMeasureSpec, heightMeasureSpec)
            val itemWidth = itemView.measuredWidth
            if (itemWidth > maxWidth) {
                maxWidth = itemWidth
            }
        }
        return maxWidth
    }

    private class SimpleMenuAdapter(
        val context: Context,
        val items: Array<CharSequence>,
        var selectedPosition: Int
    ) : BaseAdapter() {

        fun setSelectedItemPosition(position: Int) {
            selectedPosition = position
            notifyDataSetChanged()
        }

        override fun getCount() = items.size

        override fun getItem(position: Int) = items[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_1, parent, false)
            view.findViewById<TextView>(android.R.id.text1).text = items[position]
            if (position == selectedPosition) {
                view.setBackgroundColor(ContextCompat.getColor(context, R.color.dropdown_color_selected))
            }
            return view
        }

    }
}
