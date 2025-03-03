package com.example.msg

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FriendAdapter(private val friends: MutableList<String>) : RecyclerView.Adapter<FriendAdapter.FriendViewHolder>() {

    inner class FriendViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val friendAvatar: ImageView = itemView.findViewById(R.id.friendAvatar)
        val friendIdTextView: TextView = itemView.findViewById(R.id.friendIdTextView)
        //val notificationBadgeIcon: ImageView = itemView.findViewById(R.id.notificationBadgeIcon)
        val notificationBadgeText: TextView = itemView.findViewById(R.id.notificationBadgeText)
        val notificationAnimation: com.airbnb.lottie.LottieAnimationView =
            itemView.findViewById(R.id.notificationAnimation)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FriendViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.friend_item, parent, false)
        return FriendViewHolder(view)
    }

    override fun onBindViewHolder(holder: FriendViewHolder, position: Int) {
        val friendId = friends[position]
        holder.friendAvatar.setImageResource(R.drawable.default_avatar)
        holder.friendIdTextView.text = friendId

        // Обновление badge уведомлений, как было ранее
        val app = holder.itemView.context.applicationContext as MyApplication
        val count = app.unreadCounts.getOrDefault(friendId, 0)
        if (count > 0) {
            holder.notificationAnimation.visibility = View.VISIBLE
            holder.notificationBadgeText.visibility = View.VISIBLE
            holder.notificationBadgeText.text = count.toString()
        } else {
            holder.notificationAnimation.visibility = View.GONE
            holder.notificationBadgeText.visibility = View.GONE
        }

        // При обычном нажатии открывается ChatActivity
        holder.itemView.setOnClickListener {
            val context = holder.itemView.context
            val intent = Intent(context, ChatActivity::class.java).apply {
                putExtra("friend_id", friendId)
            }
            context.startActivity(intent)
        }

        // Обработка долгого нажатия для вызова опций
        holder.itemView.setOnLongClickListener {
            (holder.itemView.context as? MenuActivity)?.showFriendOptionsDialog(friendId)
            true
        }
    }



    override fun getItemCount(): Int = friends.size

    fun addFriend(friendId: String) {
        friends.add(friendId)
        notifyItemInserted(friends.size - 1)
    }

    fun removeFriend(friendId: String) {
        val index = friends.indexOf(friendId)
        if (index != -1) {
            friends.removeAt(index)
            notifyItemRemoved(index)
        }
    }
}
