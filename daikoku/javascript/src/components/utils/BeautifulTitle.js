import React, {useState} from'react';
import Popover from 'react-popover';

export const BeautifulTitle = ({ title, children, place }) => {
  const [isOpen, setIsOpen] = useState(false);

  return (
    <Popover
      isOpen={isOpen}
      preferPlace='below'
      place={place || 'below'}
      onOuterAction={() => setIsOpen(false)}
      className="beautiful-popover"
      body={title}>
      <span
        onMouseEnter={() => setIsOpen(true)}
        onMouseLeave={() => setIsOpen(false)}>
        {children}
      </span>
    </Popover>
  )
}